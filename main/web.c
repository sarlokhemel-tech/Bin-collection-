#include "web.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_ota_ops.h"
#include "esp_timer.h"
#include "cJSON.h"
#include "mbedtls/base64.h"
#include "lwip/ip4_addr.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "config.h"
#include "led.h"
#include "wifi_mgr.h"

#define FW_VER "1.0.0"
static const char *TAG = "web";

extern const uint8_t index_html_start[] asm("_binary_index_html_start");
extern const uint8_t index_html_end[]   asm("_binary_index_html_end");

/* ---------- helpers ---------- */
static bool check_auth(httpd_req_t *r)
{
    char hdr[200];
    if (httpd_req_get_hdr_value_str(r, "Authorization", hdr, sizeof hdr) == ESP_OK &&
        strncmp(hdr, "Basic ", 6) == 0) {
        unsigned char dec[160];
        size_t olen = 0;
        if (mbedtls_base64_decode(dec, sizeof dec - 1, &olen, (const unsigned char *)hdr + 6, strlen(hdr + 6)) == 0) {
            dec[olen] = 0;
            char exp[64];
            snprintf(exp, sizeof exp, "%s:%s", g_cfg.admin_user, g_cfg.admin_pass);
            if (strcmp((char *)dec, exp) == 0) return true;
        }
    }
    httpd_resp_set_status(r, "401 Unauthorized");
    httpd_resp_set_hdr(r, "WWW-Authenticate", "Basic realm=\"ESP32 Repeater\"");
    httpd_resp_send(r, "Unauthorized", HTTPD_RESP_USE_STRLEN);
    return false;
}
#define AUTH(r) do { if (!check_auth(r)) return ESP_OK; } while (0)

static cJSON *read_json(httpd_req_t *r)
{
    int len = r->content_len;
    if (len <= 0 || len > 1500) return NULL;
    char *b = malloc(len + 1);
    if (!b) return NULL;
    int got = 0;
    while (got < len) {
        int n = httpd_req_recv(r, b + got, len - got);
        if (n <= 0) { free(b); return NULL; }
        got += n;
    }
    b[len] = 0;
    cJSON *j = cJSON_Parse(b);
    free(b);
    return j;
}

static esp_err_t send_json(httpd_req_t *r, cJSON *j)
{
    char *s = cJSON_PrintUnformatted(j);
    httpd_resp_set_type(r, "application/json; charset=utf-8");
    httpd_resp_send(r, s ? s : "{}", HTTPD_RESP_USE_STRLEN);
    free(s);
    cJSON_Delete(j);
    return ESP_OK;
}

static esp_err_t ok_msg(httpd_req_t *r, bool ok, const char *msg)
{
    cJSON *j = cJSON_CreateObject();
    cJSON_AddBoolToObject(j, "ok", ok);
    cJSON_AddStringToObject(j, "msg", msg);
    return send_json(r, j);
}

static const char *jstr(cJSON *j, const char *k)
{
    cJSON *i = cJSON_GetObjectItem(j, k);
    return cJSON_IsString(i) ? i->valuestring : NULL;
}
static int jint(cJSON *j, const char *k, int d)
{
    cJSON *i = cJSON_GetObjectItem(j, k);
    return cJSON_IsNumber(i) ? i->valueint : d;
}

static void ip_str(uint32_t ip, char *o, size_t n)
{
    snprintf(o, n, "%u.%u.%u.%u", (unsigned)(ip & 255), (unsigned)((ip >> 8) & 255),
             (unsigned)((ip >> 16) & 255), (unsigned)((ip >> 24) & 255));
}

enum { ACT_REBOOT = 1, ACT_AP = 2 };
static void defer_task(void *a)
{
    int act = (int)(intptr_t)a;
    vTaskDelay(pdMS_TO_TICKS(900));
    if (act == ACT_REBOOT) esp_restart();
    else if (act == ACT_AP) wifi_mgr_apply_ap();
    vTaskDelete(NULL);
}
static void defer(int act) { xTaskCreate(defer_task, "defer", 3072, (void *)(intptr_t)act, 5, NULL); }

/* ---------- handlers ---------- */
static esp_err_t h_index(httpd_req_t *r)
{
    AUTH(r);
    httpd_resp_set_type(r, "text/html; charset=utf-8");
    return httpd_resp_send(r, (const char *)index_html_start, index_html_end - index_html_start - 1);
}

static esp_err_t h_status(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = cJSON_CreateObject();
    char b[40];
    bool up = wifi_mgr_sta_connected();
    cJSON_AddBoolToObject(j, "sta_up", up);
    cJSON_AddBoolToObject(j, "inet", wifi_mgr_inet_ok());

    wifi_ap_record_t ap;
    if (up && esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
        cJSON_AddStringToObject(j, "sta_ssid", (char *)ap.ssid);
        cJSON_AddNumberToObject(j, "rssi", ap.rssi);
    } else {
        cJSON_AddStringToObject(j, "sta_ssid", "");
        cJSON_AddNumberToObject(j, "rssi", 0);
    }
    esp_netif_ip_info_t ip;
    memset(&ip, 0, sizeof ip);
    esp_netif_get_ip_info(wifi_mgr_sta_netif(), &ip);
    uint32_t sta_ip_v = ip.ip.addr, sta_mask_v = ip.netmask.addr;
    ip_str(ip.ip.addr, b, sizeof b);   cJSON_AddStringToObject(j, "sta_ip", b);
    ip_str(ip.gw.addr, b, sizeof b);   cJSON_AddStringToObject(j, "gw", b);
    memset(&ip, 0, sizeof ip);
    esp_netif_get_ip_info(wifi_mgr_ap_netif(), &ip);
    ip_str(ip.ip.addr, b, sizeof b);   cJSON_AddStringToObject(j, "ap_ip", b);
    cJSON_AddBoolToObject(j, "conflict", up && sta_ip_v && ((sta_ip_v & sta_mask_v) == (ip.ip.addr & sta_mask_v)));
    cJSON_AddBoolToObject(j, "sta_static", g_cfg.sta_static);
    cJSON_AddStringToObject(j, "cfg_sta_ip", g_cfg.sta_ip);
    cJSON_AddStringToObject(j, "cfg_sta_mask", g_cfg.sta_mask);
    cJSON_AddStringToObject(j, "cfg_sta_gw", g_cfg.sta_gw);
    cJSON_AddStringToObject(j, "cfg_ap_ip", g_cfg.ap_ip);
    cJSON_AddStringToObject(j, "cfg_ap_mask", g_cfg.ap_mask);
    cJSON_AddNumberToObject(j, "dhcp_start", g_cfg.dhcp_start);
    cJSON_AddNumberToObject(j, "dhcp_end", g_cfg.dhcp_end);
    cJSON_AddNumberToObject(j, "lease", g_cfg.lease_min);

    uint8_t m[6];
    esp_wifi_get_mac(WIFI_IF_STA, m); mac_str(m, b); cJSON_AddStringToObject(j, "mac_sta", b);
    esp_wifi_get_mac(WIFI_IF_AP, m);  mac_str(m, b); cJSON_AddStringToObject(j, "mac_ap", b);

    cJSON_AddStringToObject(j, "cfg_sta_ssid", g_cfg.sta_ssid);
    cJSON_AddStringToObject(j, "ap_ssid", g_cfg.ap_ssid);
    cJSON_AddStringToObject(j, "ap_pass", g_cfg.ap_pass);
    cJSON_AddBoolToObject(j, "ap_hidden", g_cfg.ap_hidden);
    cJSON_AddNumberToObject(j, "ap_max", g_cfg.ap_max);
    cJSON_AddStringToObject(j, "cfg_mac_sta", g_cfg.sta_mac_en ? (mac_str(g_cfg.sta_mac, b), b) : "");
    char b2[24];
    cJSON_AddStringToObject(j, "cfg_mac_ap", g_cfg.ap_mac_en ? (mac_str(g_cfg.ap_mac, b2), b2) : "");
    cJSON_AddStringToObject(j, "hostname", g_cfg.hostname);
    cJSON_AddStringToObject(j, "dns", g_cfg.dns1);
    cJSON_AddNumberToObject(j, "tx", g_cfg.tx_dbm);
    cJSON_AddNumberToObject(j, "led_pin", g_cfg.led_pin);
    cJSON_AddBoolToObject(j, "led_en", g_cfg.led_en);
    cJSON_AddStringToObject(j, "admin_user", g_cfg.admin_user);
    cJSON_AddNumberToObject(j, "uptime", (double)(esp_timer_get_time() / 1000000));
    cJSON_AddNumberToObject(j, "heap", esp_get_free_heap_size());
    cJSON_AddStringToObject(j, "ver", FW_VER);
    return send_json(r, j);
}

static esp_err_t h_scan(httpd_req_t *r)
{
    AUTH(r);
    uint16_t n = 24;
    wifi_ap_record_t *recs = calloc(n, sizeof *recs);
    if (!recs) return ok_msg(r, false, "মেমরি কম");
    esp_err_t e = wifi_mgr_scan(recs, &n);
    if (e != ESP_OK) {
        free(recs);
        return ok_msg(r, false, "স্ক্যান ব্যর্থ, আবার চেষ্টা করুন");
    }
    cJSON *j = cJSON_CreateObject();
    cJSON *arr = cJSON_AddArrayToObject(j, "list");
    for (int i = 0; i < n; i++) {
        if (!recs[i].ssid[0]) continue;
        cJSON *o = cJSON_CreateObject();
        cJSON_AddStringToObject(o, "ssid", (char *)recs[i].ssid);
        cJSON_AddNumberToObject(o, "rssi", recs[i].rssi);
        cJSON_AddNumberToObject(o, "ch", recs[i].primary);
        cJSON_AddBoolToObject(o, "lock", recs[i].authmode != WIFI_AUTH_OPEN);
        cJSON_AddItemToArray(arr, o);
    }
    free(recs);
    return send_json(r, j);
}

static esp_err_t h_sta(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    const char *s = jstr(j, "ssid"), *p = jstr(j, "pass");
    if (!s) s = "";
    if (!p) p = "";
    if (strlen(s) > 32 || strlen(p) > 63) { cJSON_Delete(j); return ok_msg(r, false, "SSID/পাসওয়ার্ড বেশি লম্বা"); }
    strlcpy(g_cfg.sta_ssid, s, sizeof g_cfg.sta_ssid);
    strlcpy(g_cfg.sta_pass, p, sizeof g_cfg.sta_pass);
    cJSON_Delete(j);
    cfg_save();
    wifi_mgr_connect_sta();
    return ok_msg(r, true, "রাউটারে সংযোগের চেষ্টা চলছে, স্ট্যাটাস দেখুন");
}

static esp_err_t h_ap(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    const char *s = jstr(j, "ssid"), *p = jstr(j, "pass");
    if (!p) p = "";
    int mx = jint(j, "max", 8);
    if (!s || !s[0] || strlen(s) > 32) { cJSON_Delete(j); return ok_msg(r, false, "হটস্পটের নাম ১-৩২ অক্ষর হতে হবে"); }
    size_t pl = strlen(p);
    if (pl && (pl < 8 || pl > 63)) { cJSON_Delete(j); return ok_msg(r, false, "পাসওয়ার্ড ৮-৬৩ অক্ষর (বা খালি = ওপেন)"); }
    if (mx < 1) mx = 1;
    if (mx > 10) mx = 10;
    strlcpy(g_cfg.ap_ssid, s, sizeof g_cfg.ap_ssid);
    strlcpy(g_cfg.ap_pass, p, sizeof g_cfg.ap_pass);
    g_cfg.ap_hidden = cJSON_IsTrue(cJSON_GetObjectItem(j, "hidden"));
    g_cfg.ap_max = mx;
    cJSON_Delete(j);
    cfg_save();
    defer(ACT_AP);
    return ok_msg(r, true, "সেভ হয়েছে, হটস্পট রিস্টার্ট হচ্ছে — নতুন নাম/পাসওয়ার্ডে আবার কানেক্ট করুন");
}

static esp_err_t h_mac(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    const char *s = jstr(j, "sta"), *a = jstr(j, "ap");
    uint8_t ms[6], ma[6];
    bool es = s && s[0], ea = a && a[0];
    if (es && (!mac_parse(s, ms) || (ms[0] & 1))) { cJSON_Delete(j); return ok_msg(r, false, "STA MAC ভুল (প্রথম বাইট জোড় হতে হবে)"); }
    if (ea && (!mac_parse(a, ma) || (ma[0] & 1))) { cJSON_Delete(j); return ok_msg(r, false, "AP MAC ভুল (প্রথম বাইট জোড় হতে হবে)"); }
    if (es && ea && memcmp(ms, ma, 6) == 0) { cJSON_Delete(j); return ok_msg(r, false, "দুইটা MAC আলাদা হতে হবে"); }
    g_cfg.sta_mac_en = es; if (es) memcpy(g_cfg.sta_mac, ms, 6);
    g_cfg.ap_mac_en = ea;  if (ea) memcpy(g_cfg.ap_mac, ma, 6);
    cJSON_Delete(j);
    cfg_save();
    defer(ACT_REBOOT);
    return ok_msg(r, true, "MAC সেভ হয়েছে, ডিভাইস রিস্টার্ট হচ্ছে...");
}

static esp_err_t h_sys(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    const char *h = jstr(j, "host"), *d = jstr(j, "dns");
    if (!d) d = "";
    if (h) {
        size_t l = strlen(h);
        bool good = l >= 1 && l <= 32;
        for (size_t i = 0; good && i < l; i++)
            if (!((h[i] >= 'a' && h[i] <= 'z') || (h[i] >= 'A' && h[i] <= 'Z') || (h[i] >= '0' && h[i] <= '9') || h[i] == '-')) good = false;
        if (!good) { cJSON_Delete(j); return ok_msg(r, false, "হোস্টনেম: ইংরেজি অক্ষর, সংখ্যা, - (১-৩২)"); }
        strlcpy(g_cfg.hostname, h, sizeof g_cfg.hostname);
    }
    if (d[0]) {
        ip4_addr_t t;
        if (strlen(d) > 15 || !ip4addr_aton(d, &t)) { cJSON_Delete(j); return ok_msg(r, false, "DNS ঠিকানা ভুল"); }
    }
    strlcpy(g_cfg.dns1, d, sizeof g_cfg.dns1);
    int tx = jint(j, "tx", 20);
    if (tx < 2) tx = 2;
    if (tx > 20) tx = 20;
    g_cfg.tx_dbm = tx;
    int pin = jint(j, "led_pin", 2);
    if (pin < -1 || pin > 33 || (pin >= 6 && pin <= 11)) { cJSON_Delete(j); return ok_msg(r, false, "LED পিন ভুল"); }
    g_cfg.led_pin = pin;
    g_cfg.led_en = cJSON_IsTrue(cJSON_GetObjectItem(j, "led_en"));
    cJSON_Delete(j);
    cfg_save();
    esp_wifi_set_max_tx_power(tx * 4);
    led_reconfig();
    return ok_msg(r, true, "সেভ হয়েছে (হোস্টনেম/DNS রিস্টার্টের পর কার্যকর)");
}

static bool valid_ip(const char *s)
{
    ip4_addr_t a;
    return s && s[0] && strlen(s) <= 15 && ip4addr_aton(s, &a);
}

static esp_err_t h_net(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    bool st = cJSON_IsTrue(cJSON_GetObjectItem(j, "sta_static"));
    const char *ip = jstr(j, "sta_ip"), *mk = jstr(j, "sta_mask"), *gw = jstr(j, "sta_gw");
    const char *aip = jstr(j, "ap_ip"), *amk = jstr(j, "ap_mask");
    if (st && !(valid_ip(ip) && valid_ip(mk) && valid_ip(gw))) { cJSON_Delete(j); return ok_msg(r, false, "স্ট্যাটিক IP / মাস্ক / গেটওয়ে ঠিক নেই"); }
    if (!valid_ip(aip) || !valid_ip(amk)) { cJSON_Delete(j); return ok_msg(r, false, "হটস্পট IP / মাস্ক ঠিক নেই"); }
    ip4_addr_t ai, am;
    ip4addr_aton(aip, &ai);
    ip4addr_aton(amk, &am);
    if (am.addr != lwip_htonl(0xFFFFFF00UL)) { cJSON_Delete(j); return ok_msg(r, false, "হটস্পট মাস্ক 255.255.255.0 দিন"); }
    int ds = jint(j, "dhcp_start", 2), de = jint(j, "dhcp_end", 50), lm = jint(j, "lease", 120);
    int host = (ai.addr >> 24) & 255;
    if (ds < 1 || de > 254 || ds > de || host < 1 || host > 254 || (host >= ds && host <= de)) {
        cJSON_Delete(j);
        return ok_msg(r, false, "DHCP রেঞ্জ ১-২৫৪ হতে হবে, আর হটস্পটের নিজের IP রেঞ্জের বাইরে থাকতে হবে");
    }
    if (lm < 5) lm = 5;
    if (lm > 1440) lm = 1440;
    g_cfg.sta_static = st;
    strlcpy(g_cfg.sta_ip, ip ? ip : "", sizeof g_cfg.sta_ip);
    strlcpy(g_cfg.sta_mask, mk ? mk : "", sizeof g_cfg.sta_mask);
    strlcpy(g_cfg.sta_gw, gw ? gw : "", sizeof g_cfg.sta_gw);
    strlcpy(g_cfg.ap_ip, aip, sizeof g_cfg.ap_ip);
    strlcpy(g_cfg.ap_mask, amk, sizeof g_cfg.ap_mask);
    g_cfg.dhcp_start = ds; g_cfg.dhcp_end = de; g_cfg.lease_min = lm;
    cJSON_Delete(j);
    cfg_save();
    defer(ACT_REBOOT);
    return ok_msg(r, true, "সেভ হয়েছে, রিস্টার্ট হচ্ছে। হটস্পট IP বদলালে নতুন IP দিয়ে প্যানেল খুলুন");
}

static esp_err_t h_admin(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    if (!j) return ok_msg(r, false, "ভুল ডাটা");
    const char *u = jstr(j, "user"), *p = jstr(j, "pass");
    if (!u || !p || strlen(u) < 1 || strlen(u) > 16 || strlen(p) < 4 || strlen(p) > 32) {
        cJSON_Delete(j);
        return ok_msg(r, false, "ইউজার ১-১৬, পাসওয়ার্ড ৪-৩২ অক্ষর");
    }
    strlcpy(g_cfg.admin_user, u, sizeof g_cfg.admin_user);
    strlcpy(g_cfg.admin_pass, p, sizeof g_cfg.admin_pass);
    cJSON_Delete(j);
    cfg_save();
    return ok_msg(r, true, "অ্যাডমিন পাসওয়ার্ড বদলেছে — পেজ রিলোড করে নতুনটা দিন");
}

static esp_err_t h_clients(httpd_req_t *r)
{
    AUTH(r);
    ap_client_t cl[10];
    int n = wifi_mgr_ap_clients(cl, 10);
    cJSON *j = cJSON_CreateObject();
    cJSON *ar = cJSON_AddArrayToObject(j, "ap");
    char b[24];
    for (int i = 0; i < n; i++) {
        cJSON *o = cJSON_CreateObject();
        mac_str(cl[i].mac, b); cJSON_AddStringToObject(o, "mac", b);
        ip_str(cl[i].ip, b, sizeof b); cJSON_AddStringToObject(o, "ip", cl[i].ip ? b : "-");
        cJSON_AddNumberToObject(o, "rssi", cl[i].rssi);
        cJSON_AddStringToObject(o, "name", cfg_nick(cl[i].mac));
        cJSON_AddItemToArray(ar, o);
    }
    cJSON *bl = cJSON_AddArrayToObject(j, "blocked");
    for (int i = 0; i < g_cfg.block_count; i++) {
        cJSON *o = cJSON_CreateObject();
        mac_str(g_cfg.blocked[i], b); cJSON_AddStringToObject(o, "mac", b);
        cJSON_AddStringToObject(o, "name", cfg_nick(g_cfg.blocked[i]));
        cJSON_AddItemToArray(bl, o);
    }
    return send_json(r, j);
}

static esp_err_t mac_action(httpd_req_t *r, int op)   // 1 kick, 2 block, 3 unblock
{
    AUTH(r);
    cJSON *j = read_json(r);
    uint8_t m[6];
    if (!j || !mac_parse(jstr(j, "mac"), m)) { cJSON_Delete(j); return ok_msg(r, false, "MAC ভুল"); }
    cJSON_Delete(j);
    if (op == 1) {
        wifi_mgr_kick(m);
        return ok_msg(r, true, "কিক করা হয়েছে (আবার কানেক্ট করতে পারবে)");
    }
    if (op == 2) {
        if (!cfg_block_add(m)) return ok_msg(r, false, "ব্লক লিস্ট ভরে গেছে");
        cfg_save();
        wifi_mgr_kick(m);
        return ok_msg(r, true, "ব্লক করা হয়েছে");
    }
    cfg_block_del(m);
    cfg_save();
    return ok_msg(r, true, "আনব্লক করা হয়েছে");
}
static esp_err_t h_kick(httpd_req_t *r)    { return mac_action(r, 1); }
static esp_err_t h_block(httpd_req_t *r)   { return mac_action(r, 2); }
static esp_err_t h_unblock(httpd_req_t *r) { return mac_action(r, 3); }

static esp_err_t h_nick(httpd_req_t *r)
{
    AUTH(r);
    cJSON *j = read_json(r);
    uint8_t m[6];
    if (!j || !mac_parse(jstr(j, "mac"), m)) { cJSON_Delete(j); return ok_msg(r, false, "MAC ভুল"); }
    const char *n = jstr(j, "name");
    bool ok = cfg_nick_set(m, n ? n : "");
    cJSON_Delete(j);
    if (!ok) return ok_msg(r, false, "নামের লিস্ট ভরে গেছে");
    cfg_save();
    return ok_msg(r, true, "নাম সেভ হয়েছে");
}

static esp_err_t h_lan_scan(httpd_req_t *r)
{
    AUTH(r);
    if (!wifi_mgr_lan_scan_start()) return ok_msg(r, false, "আগে রাউটারে কানেক্ট করুন");
    return ok_msg(r, true, "স্ক্যান শুরু হয়েছে");
}

static esp_err_t h_lan(httpd_req_t *r)
{
    AUTH(r);
    static lan_dev_t d[64];
    int n = wifi_mgr_lan_get(d, 64);
    cJSON *j = cJSON_CreateObject();
    cJSON_AddNumberToObject(j, "state", wifi_mgr_lan_state());
    cJSON *ar = cJSON_AddArrayToObject(j, "list");
    char b[24];
    for (int i = 0; i < n; i++) {
        cJSON *o = cJSON_CreateObject();
        mac_str(d[i].mac, b); cJSON_AddStringToObject(o, "mac", b);
        ip_str(d[i].ip, b, sizeof b); cJSON_AddStringToObject(o, "ip", b);
        cJSON_AddStringToObject(o, "name", cfg_nick(d[i].mac));
        cJSON_AddItemToArray(ar, o);
    }
    return send_json(r, j);
}

static esp_err_t h_reboot(httpd_req_t *r)
{
    AUTH(r);
    defer(ACT_REBOOT);
    return ok_msg(r, true, "রিস্টার্ট হচ্ছে...");
}

static esp_err_t h_reset(httpd_req_t *r)
{
    AUTH(r);
    cfg_reset();
    defer(ACT_REBOOT);
    return ok_msg(r, true, "ফ্যাক্টরি রিসেট হয়েছে, রিস্টার্ট হচ্ছে...");
}

static esp_err_t h_ota(httpd_req_t *r)
{
    AUTH(r);
    const esp_partition_t *p = esp_ota_get_next_update_partition(NULL);
    esp_ota_handle_t h;
    if (!p || r->content_len == 0 || r->content_len > p->size)
        return ok_msg(r, false, "ফাইল সাইজ ঠিক নেই");
    if (esp_ota_begin(p, OTA_WITH_SEQUENTIAL_WRITES, &h) != ESP_OK)
        return ok_msg(r, false, "OTA শুরু করা যায়নি");
    led_set(LED_OTA);
    char *buf = malloc(2048);
    int remain = r->content_len;
    bool fail = !buf;
    while (!fail && remain > 0) {
        int n = httpd_req_recv(r, buf, remain < 2048 ? remain : 2048);
        if (n == HTTPD_SOCK_ERR_TIMEOUT) continue;
        if (n <= 0 || esp_ota_write(h, buf, n) != ESP_OK) { fail = true; break; }
        remain -= n;
    }
    free(buf);
    if (fail) {
        esp_ota_abort(h);
        led_set(wifi_mgr_sta_connected() ? LED_CONNECTED : LED_AP_ONLY);
        return ok_msg(r, false, "আপলোড ব্যর্থ");
    }
    if (esp_ota_end(h) != ESP_OK || esp_ota_set_boot_partition(p) != ESP_OK) {
        led_set(wifi_mgr_sta_connected() ? LED_CONNECTED : LED_AP_ONLY);
        return ok_msg(r, false, "bin ফাইল ঠিক নয় (merged নয়, esp32_repeater.bin দিন)");
    }
    defer(ACT_REBOOT);
    return ok_msg(r, true, "আপডেট সফল, রিস্টার্ট হচ্ছে...");
}

/* ---------- server ---------- */
typedef struct { const char *uri; httpd_method_t m; esp_err_t (*h)(httpd_req_t *); } route_t;
static const route_t ROUTES[] = {
    { "/",               HTTP_GET,  h_index },
    { "/api/status",     HTTP_GET,  h_status },
    { "/api/scan",       HTTP_GET,  h_scan },
    { "/api/sta",        HTTP_POST, h_sta },
    { "/api/ap",         HTTP_POST, h_ap },
    { "/api/mac",        HTTP_POST, h_mac },
    { "/api/sys",        HTTP_POST, h_sys },
    { "/api/admin",      HTTP_POST, h_admin },
    { "/api/net",        HTTP_POST, h_net },
    { "/api/clients",    HTTP_GET,  h_clients },
    { "/api/kick",       HTTP_POST, h_kick },
    { "/api/block",      HTTP_POST, h_block },
    { "/api/unblock",    HTTP_POST, h_unblock },
    { "/api/nick",       HTTP_POST, h_nick },
    { "/api/lan/scan",   HTTP_POST, h_lan_scan },
    { "/api/lan",        HTTP_GET,  h_lan },
    { "/api/reboot",     HTTP_POST, h_reboot },
    { "/api/reset",      HTTP_POST, h_reset },
    { "/api/ota",        HTTP_POST, h_ota },
};

void web_start(void)
{
    httpd_config_t c = HTTPD_DEFAULT_CONFIG();
    c.max_uri_handlers = 32;
    c.stack_size = 8192;
    c.lru_purge_enable = true;
    c.recv_wait_timeout = 15;
    c.send_wait_timeout = 15;
    httpd_handle_t s = NULL;
    if (httpd_start(&s, &c) != ESP_OK) { ESP_LOGE(TAG, "httpd start failed"); return; }
    for (size_t i = 0; i < sizeof ROUTES / sizeof ROUTES[0]; i++) {
        httpd_uri_t u = { .uri = ROUTES[i].uri, .method = ROUTES[i].m, .handler = ROUTES[i].h };
        httpd_register_uri_handler(s, &u);
    }
}
