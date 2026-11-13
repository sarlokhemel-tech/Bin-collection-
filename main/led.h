#pragma once
#include <stdint.h>

typedef enum {
    LED_OFF = 0,
    LED_AP_ONLY,     // router not set: slow blink
    LED_CONNECTING,  // trying router: fast blink
    LED_CONNECTED,   // router connected: solid ON
    LED_SCAN,        // scanning: double blink
    LED_ERROR,       // router failed repeatedly: 3 flashes + pause
    LED_OTA,         // updating / resetting: very fast
    LED_CLIENT,      // phone joined hotspot: 4 quick flashes
    LED_NOINET,      // router connected but no internet: mostly on, short off blink
    LED_MAX
} led_state_t;

void led_init(void);
void led_set(led_state_t s);                    // base state
void led_signal(led_state_t s, uint32_t ms);    // temporary override
void led_reconfig(void);                        // re-read pin/enable from config
