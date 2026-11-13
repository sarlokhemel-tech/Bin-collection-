#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "esp_wifi.h"
#include "esp_netif.h"

typedef struct { uint8_t mac[6]; uint32_t ip; int8_t rssi; } ap_client_t;
typedef struct { uint8_t mac[6]; uint32_t ip; } lan_dev_t;

void wifi_mgr_init(void);
void wifi_mgr_connect_sta(void);     // apply g_cfg router settings and (re)connect
void wifi_mgr_apply_ap(void);        // apply g_cfg hotspot settings
bool wifi_mgr_sta_connected(void);
bool wifi_mgr_inet_ok(void);          // real internet reachable through the router
esp_netif_t *wifi_mgr_sta_netif(void);
esp_netif_t *wifi_mgr_ap_netif(void);

esp_err_t wifi_mgr_scan(wifi_ap_record_t *out, uint16_t *n);   // n in: capacity, out: found
int  wifi_mgr_ap_clients(ap_client_t *out, int max);
bool wifi_mgr_kick(const uint8_t *mac);

bool wifi_mgr_lan_scan_start(void);  // false if STA not connected
int  wifi_mgr_lan_state(void);       // 0 idle, 1 running, 2 done
int  wifi_mgr_lan_get(lan_dev_t *out, int max);
