/* Calls from native code into MainActivity (Kotlin). */
#ifndef DXG_BRIDGE_H
#define DXG_BRIDGE_H

/* The place label under the crosshair; place "" hides it. */
void bridge_globe_label(const char *place, const char *detail);
/* A light haptic tick. */
void bridge_haptic_tick(void);

#endif
