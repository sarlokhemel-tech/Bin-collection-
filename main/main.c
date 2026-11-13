#include "nvs_flash.h"
#include "esp_system.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "config.h"
#include "led.h"
#include "wifi_mgr.h"
#include "web.h"

// Hold BOOT (GPIO0) for 5 seconds while running = factory reset
static void btn_task(void *a)
{
    gpio_set_direction(GPIO_NUM_0, GPIO_MODE_INPUT);
    gpio_set_pull_mode(GPIO_NUM_0, GPIO_PULLUP_ONLY);
    int held = 0;
    for (;;) {
        if (gpio_get_level(GPIO_NUM_0) == 0) {
            if (++held >= 50) {
                led_signal(LED_OTA, 5000);
                cfg_reset();
                vTaskDelay(pdMS_TO_TICKS(800));
                esp_restart();
            }
        } else {
            held = 0;
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void app_main(void)
{
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }
    cfg_load();
    led_init();
    wifi_mgr_init();
    web_start();
    xTaskCreate(btn_task, "btn", 2048, NULL, 3, NULL);
}
