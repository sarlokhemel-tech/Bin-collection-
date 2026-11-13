#include "wifi_mgr.h"
#include <string.h>
#include "config.h"
#include "led.h"
#include "esp_mac.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "lwip/lwip_napt.h"
#include "lwip/etharp.h"
#include "lwip/tcpip.h"
#include "lwip/ip4_addr.h"
#include "lwip/sockets.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "wifi";
static esp_netif_t *s_sta, *s_ap;
static volatile bool s_sta_ip = false;
static volatile bool s_scan_hold = false;
static int s_retry = 0;
static esp_timer_handle_t s_reconn;

/* ---------- helpers ---------- */
static void fill_ap_cfg(wifi_config_t *w)
{
    memset(w, 0, sizeof *w);
    strlcpy((char *)w->ap.ssid, g_cfg.ap_ssid, sizeof w->ap.ssid);
    w->ap.ssid_len = strlen(g_cfg.ap_ssid);
    strlcpy((char *)w->ap.password, g_cfg.ap_pass, sizeof w->ap.password);
    w->ap.max_connection = g_cfg.ap_max ? g_cfg.ap_max : 8;
    w->ap.authmode = strlen(g_cfg.ap_pass) >= 8 ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
    w->ap.ssid_hidden = g_cfg.ap_hidden ? 1 : 0;
    w->ap.channel = 1;               // follows the router channel automatically when connected
    w->ap.beacon_interval = 100;
}

static void apply_sta_cfg(void)
{
    wifi_config_t w;
    memset(&w, 0, sizeof w);
    strlcpy((char *)w.sta.ssid, g_cfg.sta_ssid, sizeof w.sta.ssid);
    strlcpy((char *)w.sta.password, g_cfg.sta_pass, sizeof w.sta.password);
    w.sta.pmf_cfg.capable = true;
    w.sta.pmf_cfg.required = false;
    esp_wifi_set_config(WIFI_IF_STA, &w);
}

static bool parse_ip(const char *s, uint32_t *o)
{
    ip4_addr_t a;
    if (!s || !s[0] || !ip4addr_aton(s, &a)) return false;
    *o = a.addr;
    return true;
}

typedef struct { bool enable; esp_ip4_addr_t start_ip; esp_ip4_addr_t end_ip; } my_lease_t;

/* (re)start the hotspot DHCP server with our range, lease time and optional DNS */
static void ap_dhcp_start(const esp_netif_dns_info_t *dns)
{
    esp_netif_dhcps_stop(s_ap);
    uint32_t ip, mask;
    if (parse_ip(g_cfg.ap_ip, &ip) && parse_ip(g_cfg.ap_mask, &mask)) {
        uint32_t base = ip & mask;            // network byte order: last octet = top byte
        my_lease_t l;
        memset(&l, 0, sizeof l);
        l.enable = true;
        l.start_ip.addr = base | ((uint32_t)g_cfg.dhcp_start << 24);
        l.end_ip.addr = base | ((uint32_t)g_cfg.dhcp_end << 24);
        esp_netif_dhcps_option(s_ap, ESP_NETIF_OP_SET, ESP_NETIF_REQUESTED_IP_ADDRESS, &l, sizeof l);
    }
    uint32_t lease = g_cfg.lease_min ? g_cfg.lease_min : 120;
    esp_netif_dhcps_option(s_ap, ESP_NETIF_OP_SET, ESP_NETIF_IP_ADDRESS_LEASE_TIME, &lease, sizeof lease);
    if (dns) {
        esp_netif_set_dns_info(s_ap, ESP_NETIF_DNS_MAIN, (esp_netif_dns_info_t *)dns);
        uint8_t offer = 0x02;   // OFFER_DNS
        esp_netif_dhcps_option(s_ap, ESP_NETIF_OP_SET, ESP_NETIF_DOMAIN_NAME_SERVER, &offer, sizeof offer);
    }
    esp_netif_dhcps_start(s_ap);
}

/* DNS offered to phones. Public DNS by default so phones never get an empty DNS
   (an empty/late DNS is the main reason for "connected, no internet"). */
static void default_dns(esp_netif_dns_info_t *d)
{
    memset(d, 0, sizeof *d);
    d->ip.type = ESP_IPADDR_TYPE_V4;
    uint32_t a;
    d->ip.u_addr.ip4.addr = parse_ip(g_cfg.dns1, &a) ? a : ESP_IP4TOUINT32(8, 8, 8, 8);
}

static void apply_ap_ip(void)
{
    uint32_t ip, m;
    if (!parse_ip(g_cfg.ap_ip, &ip) || !parse_ip(g_cfg.ap_mask, &m)) return;
    esp_netif_dhcps_stop(s_ap);
    esp_netif_ip_info_t i;
    memset(&i, 0, sizeof i);
    i.ip.addr = ip; i.gw.addr = ip; i.netmask.addr = m;
    esp_netif_set_ip_info(s_ap, &i);
    esp_netif_dns_info_t d;
    default_dns(&d);
    ap_dhcp_start(&d);
}

static void apply_sta_ip(void)
{
    if (!g_cfg.sta_static) return;
    uint32_t ip, m, gw;
    if (!parse_ip(g_cfg.sta_ip, &ip) || !parse_ip(g_cfg.sta_mask, &m) || !parse_ip(g_cfg.sta_gw, &gw)) return;
    esp_netif_dhcpc_stop(s_sta);
    esp_netif_ip_info_t i;
    memset(&i, 0, sizeof i);
    i.ip.addr = ip; i.netmask.addr = m; i.gw.addr = gw;
    esp_netif_set_ip_info(s_sta, &i);
    esp_netif_dns_info_t d;
    memset(&d, 0, sizeof d);
    d.ip.type = ESP_IPADDR_TYPE_V4;
    uint32_t dn;
    d.ip.u_addr.ip4.addr = parse_ip(g_cfg.dns1, &dn) ? dn : ESP_IP4TOUINT32(8, 8, 8, 8);
    esp_netif_set_dns_info(s_sta, ESP_NETIF_DNS_MAIN, &d);
}

/* If router subnet == hotspot subnet, NAT cannot work -> move hotspot to a free subnet automatically */
static void resolve_conflict(const esp_netif_ip_info_t *si)
{
    static const char *cands[] = { "192.168.50.1", "192.168.60.1", "172.16.77.1", "10.77.77.1" };
    for (size_t i = 0; i < sizeof cands / sizeof cands[0]; i++) {
        uint32_t c;
        if (!parse_ip(cands[i], &c)) continue;
        if ((c & si->netmask.addr) != (si->ip.addr & si->netmask.addr)) {
            strlcpy(g_cfg.ap_ip, cands[i], sizeof g_cfg.ap_ip);
            strcpy(g_cfg.ap_mask, "255.255.255.0");
            cfg_save();
            esp_wifi_deauth_sta(0);      // kick all phones, they reconnect and get new IPs
            apply_ap_ip();
            ESP_LOGW(TAG, "subnet conflict -> hotspot moved to %s", cands[i]);
            return;
        }
    }
}

/* After router gives us an IP: fix subnet clash, (re)enable NAT */
static void setup_dns_nat(void)
{
    esp_netif_ip_info_t si, ai;
    memset(&si, 0, sizeof si);
    memset(&ai, 0, sizeof ai);
    esp_netif_get_ip_info(s_sta, &si);
    esp_netif_get_ip_info(s_ap, &ai);
    if (si.ip.addr && ((si.ip.addr & si.netmask.addr) == (ai.ip.addr & si.netmask.addr))) {
        resolve_conflict(&si);
        esp_netif_get_ip_info(s_ap, &ai);
    }
    ip_napt_enable(ai.ip.addr, 1);
    ESP_LOGI(TAG, "NAT enabled");
}

static void reconn_cb(void *a)
{
    if (s_scan_hold || s_sta_ip || !g_cfg.sta_ssid[0]) return;
    esp_wifi_connect();
}

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT) {
        switch (id) {
        case WIFI_EVENT_STA_START:
            if (g_cfg.sta_ssid[0]) { led_set(LED_CONNECTING); esp_wifi_connect(); }
            else led_set(LED_AP_ONLY);
            break;
        case WIFI_EVENT_STA_DISCONNECTED:
            s_sta_ip = false;
            if (g_cfg.sta_ssid[0]) {
                s_retry++;
                led_set(s_retry >= 5 ? LED_ERROR : LED_CONNECTING);
                if (!s_scan_hold) {
                    esp_timer_stop(s_reconn);
                    esp_timer_start_once(s_reconn, s_retry >= 5 ? 10000000 : 2000000);
                }
            } else {
                led_set(LED_AP_ONLY);
            }
            break;
        case WIFI_EVENT_AP_STACONNECTED: {
            wifi_event_ap_staconnected_t *e = data;
            if (cfg_is_blocked(e->mac)) esp_wifi_deauth_sta(e->aid);
            else led_signal(LED_CLIENT, 2000);
            break;
        }
        default: break;
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        s_sta_ip = true;
        s_retry = 0;
        setup_dns_nat();
        led_set(LED_CONNECTED);
    }
}

/* ---------- internet watchdog ---------- */
static volatile bool s_inet = false;

static bool probe(uint32_t ip)
{
    int sk = socket(AF_INET, SOCK_STREAM, 0);
    if (sk < 0) return false;
    fcntl(sk, F_SETFL, fcntl(sk, F_GETFL, 0) | O_NONBLOCK);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(53);
    a.sin_addr.s_addr = ip;
    connect(sk, (struct sockaddr *)&a, sizeof a);
    fd_set w;
    FD_ZERO(&w);
    FD_SET(sk, &w);
    struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
    bool ok = false;
    if (select(sk + 1, NULL, &w, NULL, &tv) > 0) {
        int err = 1;
        socklen_t l = sizeof err;
        getsockopt(sk, SOL_SOCKET, SO_ERROR, &err, &l);
        ok = (err == 0);
    }
    close(sk);
    return ok;
}

static void inet_task(void *a)
{
    int fails = 0;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(15000));
        if (!s_sta_ip) { s_inet = false; fails = 0; continue; }
        bool ok = probe(ESP_IP4TOUINT32(1, 1, 1, 1)) || probe(ESP_IP4TOUINT32(8, 8, 8, 8));
        if (ok) {
            s_inet = true;
            if (fails) led_set(LED_CONNECTED);
            fails = 0;
            continue;
        }
        s_inet = false;
        fails++;
        led_set(LED_NOINET);
        if (fails == 3 || fails == 12) esp_wifi_disconnect();   // ~45 s / ~3 min: reconnect to router
        if (fails >= 60) esp_restart();                         // ~15 min: full reboot
    }
}

/* ---------- public ---------- */
void wifi_mgr_init(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    s_sta = esp_netif_create_default_wifi_sta();
    s_ap = esp_netif_create_default_wifi_ap();
    esp_netif_set_hostname(s_sta, g_cfg.hostname);
    apply_ap_ip();
    apply_sta_ip();

    const esp_timer_create_args_t ta = { .callback = reconn_cb, .name = "reconn" };
    ESP_ERROR_CHECK(esp_timer_create(&ta, &s_reconn));

    wifi_init_config_t ic = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&ic));
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL));

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));

    if (g_cfg.sta_mac_en && !(g_cfg.sta_mac[0] & 1)) esp_wifi_set_mac(WIFI_IF_STA, g_cfg.sta_mac);
    if (g_cfg.ap_mac_en && !(g_cfg.ap_mac[0] & 1))   esp_wifi_set_mac(WIFI_IF_AP, g_cfg.ap_mac);

    wifi_config_t ap;
    fill_ap_cfg(&ap);
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    apply_sta_cfg();

    ESP_ERROR_CHECK(esp_wifi_start());
    esp_wifi_set_ps(WIFI_PS_NONE);
    int q = g_cfg.tx_dbm * 4;
    if (q < 8) q = 8;
    if (q > 80) q = 80;
    esp_wifi_set_max_tx_power(q);

    esp_netif_ip_info_t ai;
    if (esp_netif_get_ip_info(s_ap, &ai) == ESP_OK) ip_napt_enable(ai.ip.addr, 1);   // NAT ready from boot
    xTaskCreate(inet_task, "inet", 3072, NULL, 3, NULL);
}

void wifi_mgr_connect_sta(void)
{
    apply_sta_cfg();
    s_retry = 0;
    esp_timer_stop(s_reconn);
    if (!g_cfg.sta_ssid[0]) {
        esp_wifi_disconnect();
        led_set(LED_AP_ONLY);
        return;
    }
    led_set(LED_CONNECTING);
    esp_wifi_disconnect();
    esp_timer_start_once(s_reconn, 700000);
}

void wifi_mgr_apply_ap(void)
{
    wifi_config_t ap;
    fill_ap_cfg(&ap);
    esp_wifi_set_config(WIFI_IF_AP, &ap);
}

bool wifi_mgr_sta_connected(void) { return s_sta_ip; }
bool wifi_mgr_inet_ok(void) { return s_inet; }
esp_netif_t *wifi_mgr_sta_netif(void) { return s_sta; }
esp_netif_t *wifi_mgr_ap_netif(void) { return s_ap; }

esp_err_t wifi_mgr_scan(wifi_ap_record_t *out, uint16_t *n)
{
    led_signal(LED_SCAN, 5000);
    s_scan_hold = true;
    esp_timer_stop(s_reconn);
    if (!s_sta_ip) esp_wifi_disconnect();
    wifi_scan_config_t sc = {
        .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time.active.min = 80,
        .scan_time.active.max = 150,
    };
    esp_err_t e = esp_wifi_scan_start(&sc, true);
    if (e == ESP_OK) e = esp_wifi_scan_get_ap_records(n, out);
    s_scan_hold = false;
    if (!s_sta_ip && g_cfg.sta_ssid[0]) esp_timer_start_once(s_reconn, 500000);
    return e;
}

int wifi_mgr_ap_clients(ap_client_t *out, int max)
{
    wifi_sta_list_t wl;
    memset(&wl, 0, sizeof wl);
    if (esp_wifi_ap_get_sta_list(&wl) != ESP_OK) return 0;
    wifi_sta_mac_ip_list_t nl;
    memset(&nl, 0, sizeof nl);
    esp_wifi_ap_get_sta_list_with_ip(&wl, &nl);
    int c = 0;
    for (int i = 0; i < wl.num && c < max; i++) {
        memcpy(out[c].mac, wl.sta[i].mac, 6);
        out[c].rssi = wl.sta[i].rssi;
        out[c].ip = 0;
        for (int j = 0; j < nl.num; j++)
            if (memcmp(nl.sta[j].mac, wl.sta[i].mac, 6) == 0) out[c].ip = nl.sta[j].ip.addr;
        c++;
    }
    return c;
}

bool wifi_mgr_kick(const uint8_t *mac)
{
    uint16_t aid;
    if (esp_wifi_ap_get_sta_aid(mac, &aid) != ESP_OK) return false;
    return esp_wifi_deauth_sta(aid) == ESP_OK;
}

/* ---------- router-side device scan (ARP sweep of the /24) ---------- */
#define LAN_MAX 64
static lan_dev_t s_lan[LAN_MAX];
static volatile int s_lan_n = 0;
static volatile int s_lan_state = 0;

typedef struct { struct netif *nif; uint32_t base; int from, to; uint32_t self; } arp_batch_t;
static arp_batch_t s_batch;
static volatile bool s_cb_done;

static void arp_req_cb(void *a)
{
    for (int i = s_batch.from; i <= s_batch.to; i++) {
        ip4_addr_t t;
        t.addr = lwip_htonl(s_batch.base | (uint32_t)i);
        if (t.addr == s_batch.self) continue;
        etharp_request(s_batch.nif, &t);
    }
    s_cb_done = true;
}

static void arp_read_cb(void *a)
{
    for (size_t k = 0; k < ARP_TABLE_SIZE; k++) {
        ip4_addr_t *ipa = NULL;
        struct netif *n = NULL;
        struct eth_addr *e = NULL;
        if (etharp_get_entry(k, &ipa, &n, &e) && n == s_batch.nif && ipa && e) {
            bool dup = false;
            for (int i = 0; i < s_lan_n; i++)
                if (memcmp(s_lan[i].mac, e->addr, 6) == 0) { dup = true; break; }
            if (!dup && s_lan_n < LAN_MAX) {
                memcpy(s_lan[s_lan_n].mac, e->addr, 6);
                s_lan[s_lan_n].ip = ipa->addr;
                s_lan_n++;
            }
        }
    }
    s_cb_done = true;
}

static void run_cb(tcpip_callback_fn fn)
{
    s_cb_done = false;
    if (tcpip_callback(fn, NULL) != ERR_OK) return;
    for (int i = 0; i < 100 && !s_cb_done; i++) vTaskDelay(pdMS_TO_TICKS(10));
}

static void lan_task(void *a)
{
    esp_netif_ip_info_t ip;
    if (esp_netif_get_ip_info(s_sta, &ip) != ESP_OK || ip.ip.addr == 0) {
        s_lan_state = 0;
        vTaskDelete(NULL);
        return;
    }
    s_batch.nif = (struct netif *)esp_netif_get_netif_impl(s_sta);
    s_batch.base = lwip_ntohl(ip.ip.addr) & 0xFFFFFF00u;
    s_batch.self = ip.ip.addr;

    for (int start = 1; start <= 254; start += 8) {
        s_batch.from = start;
        s_batch.to = (start + 7 > 254) ? 254 : start + 7;
        run_cb(arp_req_cb);
        vTaskDelay(pdMS_TO_TICKS(400));
        run_cb(arp_read_cb);
    }
    s_lan_state = 2;
    vTaskDelete(NULL);
}

bool wifi_mgr_lan_scan_start(void)
{
    if (!s_sta_ip) return false;
    if (s_lan_state == 1) return true;
    s_lan_n = 0;
    s_lan_state = 1;
    led_signal(LED_SCAN, 15000);
    xTaskCreate(lan_task, "lan", 4096, NULL, 4, NULL);
    return true;
}

int wifi_mgr_lan_state(void) { return s_lan_state; }

int wifi_mgr_lan_get(lan_dev_t *out, int max)
{
    int n = s_lan_n;
    if (n > max) n = max;
    memcpy(out, s_lan, n * sizeof(lan_dev_t));
    return n;
}
