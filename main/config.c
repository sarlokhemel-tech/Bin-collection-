#include "config.h"
#include <string.h>
#include <stdio.h>
#include "nvs.h"
#include "nvs_flash.h"

#define CFG_MAGIC 0x4E5A3103u
app_cfg_t g_cfg;

static void defaults(void)
{
    memset(&g_cfg, 0, sizeof g_cfg);
    g_cfg.magic = CFG_MAGIC;
    strcpy(g_cfg.ap_ssid, "ESP32-Repeater");
    strcpy(g_cfg.ap_pass, "12345678");
    g_cfg.ap_max = 8;
    strcpy(g_cfg.admin_user, "admin");
    strcpy(g_cfg.admin_pass, "admin");
    strcpy(g_cfg.hostname, "esp32-repeater");
    g_cfg.led_pin = 2;
    g_cfg.led_en = true;
    g_cfg.tx_dbm = 20;
    strcpy(g_cfg.ap_ip, "192.168.4.1");
    strcpy(g_cfg.ap_mask, "255.255.255.0");
    g_cfg.dhcp_start = 2;
    g_cfg.dhcp_end = 50;
    g_cfg.lease_min = 120;
}

void cfg_load(void)
{
    static app_cfg_t tmp;
    size_t len = sizeof tmp;
    nvs_handle_t h;
    if (nvs_open("rep", NVS_READONLY, &h) == ESP_OK) {
        esp_err_t e = nvs_get_blob(h, "cfg", &tmp, &len);
        nvs_close(h);
        if (e == ESP_OK && len == sizeof tmp && tmp.magic == CFG_MAGIC) {
            g_cfg = tmp;
            return;
        }
    }
    defaults();
}

void cfg_save(void)
{
    nvs_handle_t h;
    if (nvs_open("rep", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_blob(h, "cfg", &g_cfg, sizeof g_cfg);
        nvs_commit(h);
        nvs_close(h);
    }
}

void cfg_reset(void)
{
    defaults();
    cfg_save();
}

bool mac_parse(const char *s, uint8_t *out)
{
    unsigned v[6];
    if (!s) return false;
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return false;
    for (int i = 0; i < 6; i++) { if (v[i] > 255) return false; out[i] = (uint8_t)v[i]; }
    return true;
}

void mac_str(const uint8_t *m, char *o)
{
    snprintf(o, 18, "%02x:%02x:%02x:%02x:%02x:%02x", m[0], m[1], m[2], m[3], m[4], m[5]);
}

bool cfg_is_blocked(const uint8_t *mac)
{
    for (int i = 0; i < g_cfg.block_count; i++)
        if (memcmp(g_cfg.blocked[i], mac, 6) == 0) return true;
    return false;
}

bool cfg_block_add(const uint8_t *mac)
{
    if (cfg_is_blocked(mac)) return true;
    if (g_cfg.block_count >= MAX_BLOCK) return false;
    memcpy(g_cfg.blocked[g_cfg.block_count++], mac, 6);
    return true;
}

void cfg_block_del(const uint8_t *mac)
{
    for (int i = 0; i < g_cfg.block_count; i++) {
        if (memcmp(g_cfg.blocked[i], mac, 6) == 0) {
            for (int j = i; j < g_cfg.block_count - 1; j++)
                memcpy(g_cfg.blocked[j], g_cfg.blocked[j + 1], 6);
            g_cfg.block_count--;
            return;
        }
    }
}

const char *cfg_nick(const uint8_t *mac)
{
    for (int i = 0; i < MAX_NICK; i++)
        if (g_cfg.nicks[i].name[0] && memcmp(g_cfg.nicks[i].mac, mac, 6) == 0)
            return g_cfg.nicks[i].name;
    return "";
}

bool cfg_nick_set(const uint8_t *mac, const char *name)
{
    int free_i = -1;
    for (int i = 0; i < MAX_NICK; i++) {
        if (g_cfg.nicks[i].name[0] && memcmp(g_cfg.nicks[i].mac, mac, 6) == 0) {
            if (!name || !name[0]) { memset(&g_cfg.nicks[i], 0, sizeof(nick_t)); return true; }
            strlcpy(g_cfg.nicks[i].name, name, sizeof g_cfg.nicks[i].name);
            return true;
        }
        if (!g_cfg.nicks[i].name[0] && free_i < 0) free_i = i;
    }
    if (!name || !name[0]) return true;
    if (free_i < 0) return false;
    memcpy(g_cfg.nicks[free_i].mac, mac, 6);
    strlcpy(g_cfg.nicks[free_i].name, name, sizeof g_cfg.nicks[free_i].name);
    return true;
}
