/* Calls from native code into MainActivity (Kotlin). */
#ifndef DXG_BRIDGE_H
#define DXG_BRIDGE_H

/* The place label under the crosshair; place "" hides it. */
void bridge_globe_label(const char *place, const char *detail);
/* A light haptic tick. */
void bridge_haptic_tick(void);
/* What's playing, for the media session and notification (Playback.kt):
 * state is one of Playback.kt's (0 stopped, 1 connecting, 2 playing,
 * 3 paused, 4 error); detail is the title playing or the error. */
void bridge_playback(int state, const char *station, const char *detail, const char *place);

#endif
