#include "led.h"
#include "config.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// one char = 100 ms
static const char *PAT[LED_MAX] = {
    [LED_OFF]        = "0",
    [LED_AP_ONLY]    = "1111100000",
    [LED_CONNECTING] = "1100",
    [LED_CONNECTED]  = "1",
    [LED_SCAN]       = "1010000000",
    [LED_ERROR]      = "1010100000000000",
    [LED_OTA]        = "10",
    [LED_CLIENT]     = "10101010000000",
    [LED_NOINET]     = "1110",
};

static volatile led_state_t s_base = LED_AP_ONLY;
static volatile int s_over = -1;
static volatile TickType_t s_end;
static volatile bool s_reconf = false;
static int s_pin = -1;

static void pin_apply(void)
{
    int np = -1;
    int p = g_cfg.led_pin;
    if (g_cfg.led_en && p >= 0 && p <= 33 && !(p >= 6 && p <= 11)) np = p;
    if (np == s_pin) return;
    if (s_pin >= 0) gpio_set_level(s_pin, 0);
    s_pin = np;
    if (np >= 0) {
        gpio_reset_pin(np);
        gpio_set_direction(np, GPIO_MODE_OUTPUT);
        gpio_set_level(np, 0);
    }
}

static void led_task(void *a)
{
    int i = 0;
    led_state_t last = LED_MAX;
    pin_apply();
    for (;;) {
        if (s_reconf) { s_reconf = false; pin_apply(); }
        led_state_t s = s_base;
        if (s_over >= 0) {
            if ((int32_t)(xTaskGetTickCount() - s_end) < 0) s = (led_state_t)s_over;
            else s_over = -1;
        }
        if (s != last) { last = s; i = 0; }
        const char *p = PAT[s];
        if (!p[i]) i = 0;
        if (s_pin >= 0) gpio_set_level(s_pin, p[i] == '1');
        i++;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void led_init(void) { xTaskCreate(led_task, "led", 2048, NULL, 3, NULL); }
void led_set(led_state_t s) { s_base = s; }
void led_signal(led_state_t s, uint32_t ms)
{
    s_end = xTaskGetTickCount() + pdMS_TO_TICKS(ms);
    s_over = (int)s;
}
void led_reconfig(void) { s_reconf = true; }
