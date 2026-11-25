/* What main.c shares with the interface's JNI functions (ui_jni.c).
 * The library is read-only once loaded, so any thread may read it after
 * app_library() returns it. Favourites and recent are behind a lock. */
#ifndef ENSO_APP_H
#define ENSO_APP_H

#include "library.h"

/* Commands from the interface (Playback.kt and Ui.kt use the same numbers). */
enum {
    CMD_PLAY = 1,
    CMD_PAUSE,
    CMD_STOP,
    CMD_NEXT,
    CMD_PREVIOUS,
    CMD_PLAY_STATION = 10, /* arg: station index */
    CMD_SHOW_PLACE,        /* arg: place index; turns there and plays it */
    CMD_PREBUFFER          /* arg: KB before playing starts */
};

/* The library, or NULL until it has loaded. */
const library *app_library(void);
/* Queue a command for the main loop (any thread). */
void app_command(int cmd, int arg);
/* Where the globe goes: the window less a panel on the left (landscape).
 * Any thread. */
void app_set_globe_left(int px);

/* Favourites and recent: station indices, newest recent first. Returns
 * how many were written (up to max). which: 0 favourites, 1 recent. */
int app_list(int which, int *out, int max);
int app_list_count(int which);
int app_is_favourite(int station);
/* Returns 1 if it is a favourite now. Saves favourites.txt. */
int app_toggle_favourite(int station);

#endif
