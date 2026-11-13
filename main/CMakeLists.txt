idf_component_register(
    SRCS "main.c" "config.c" "led.c" "wifi_mgr.c" "web.c"
    INCLUDE_DIRS "."
    REQUIRES esp_wifi esp_netif esp_event nvs_flash esp_http_server json lwip
             driver app_update esp_partition esp_timer mbedtls esp_system esp_hw_support
    EMBED_TXTFILES "web/index.html")
