#pragma once
#include <stdint.h>
#include <stdbool.h>

#define MAX_BLOCK 16
#define MAX_NICK  16

typedef struct { uint8_t mac[6]; char name[24]; } nick_t;

typedef struct {
    uint32_t magic;
    char sta_ssid[33];  char sta_pass[65];     // router
    char ap_ssid[33];   char ap_pass[65];      // own hotspot
    bool ap_hidden;     uint8_t ap_max;
    bool sta_mac_en;    uint8_t sta_mac[6];
    bool ap_mac_en;     uint8_t ap_mac[6];
    char admin_user[17]; char admin_pass[33];
    char hostname[33];
    char dns1[16];                              // optional custom DNS
    int8_t led_pin;     bool led_en;
    uint8_t tx_dbm;
    bool sta_static; char sta_ip[16]; char sta_mask[16]; char sta_gw[16];   // static IP on router side
    char ap_ip[16]; char ap_mask[16];                                       // hotspot IP
    uint8_t dhcp_start; uint8_t dhcp_end; uint16_t lease_min;               // DHCP range (last octet), lease minutes
    uint8_t block_count; uint8_t blocked[MAX_BLOCK][6];
    nick_t nicks[MAX_NICK];
} app_cfg_t;

extern app_cfg_t g_cfg;

void cfg_load(void);
void cfg_save(void);
void cfg_reset(void);

bool mac_parse(const char *s, uint8_t *out);
void mac_str(const uint8_t *mac, char *out);   // out >= 18 bytes

bool cfg_is_blocked(const uint8_t *mac);
bool cfg_block_add(const uint8_t *mac);
void cfg_block_del(const uint8_t *mac);
const char *cfg_nick(const uint8_t *mac);
bool cfg_nick_set(const uint8_t *mac, const char *name);
