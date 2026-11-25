/* Calls from native code into MainActivity (Kotlin), and Java strings. */
#ifndef ENSO_BRIDGE_H
#define ENSO_BRIDGE_H

#include <jni.h>

/* A Java string from UTF-8 (bad bytes become U+FFFD). Unlike
 * NewStringUTF, safe for any text, emoji included. */
jstring bridge_jstring(JNIEnv *env, const char *utf8);

/* The place under the crosshair (index or -1), its name and a detail. */
void bridge_globe_label(int place, const char *name, const char *detail);
/* A light haptic tick. */
void bridge_haptic_tick(void);
/* The library has loaded (station count). */
void bridge_library_ready(int stations);
/* What's playing, for the media session and the interface: state is one
 * of Playback.kt's (0 stopped, 1 connecting, 2 playing, 3 paused,
 * 4 error); station is its index or -1; detail is the title playing or
 * the error. */
void bridge_playback(int state, int station, const char *name, const char *detail,
                     const char *place);

#endif
