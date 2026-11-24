/*
 * The globe by touch: the camera, gestures, the crosshair that tunes to
 * the place under it, and the place label. Drawing is earth.c's.
 *
 * Gestures (NOTES.md, "The globe by touch"): one-finger drag spins it,
 * with momentum when flung; pinch zooms; double-tap zooms in where tapped;
 * two-finger tap zooms out; a tap on a dot plays that place.
 */
#ifndef DXG_GLOBE_VIEW_H
#define DXG_GLOBE_VIEW_H

#include <SDL3/SDL.h>

#include "config.h"
#include "library.h"

typedef struct {
    void (*play)(int station);  /* start a station (index into lib->stations) */
    int (*playing)(void);       /* the station playing, or -1 */
    void (*label)(const char *place, const char *detail); /* the place label */
    void (*tick)(void);         /* a light haptic tick: tuned to a new place */
} globeview_hooks;

/* Needs a current GL context. Reads the saved camera from cfg. */
void globeview_init(const library *lib, const config *cfg, const globeview_hooks *hooks,
                    const char *day_pack, const char *night_pack);
void globeview_shutdown(void);
/* Saves the camera into cfg. */
void globeview_save(config *cfg);
/* Touch events (SDL_EVENT_FINGER_*). w, h: the window in pixels. */
void globeview_event(const SDL_Event *e, int w, int h);
/* Once a frame: motion, tuning and drawing. density: pixels per dp.
 * unix_s: the time for the Sun, or 0 for now. */
void globeview_frame(int w, int h, float density, double unix_s);
/* The GL context was lost and remade. */
void globeview_gl_reset(void);

/* For scripted tests: the camera, set at once (alt <= 0 keeps it). */
void globeview_goto(double lat, double lon, double alt);
void globeview_camera(double *lat, double *lon, double *alt);
/* The place under the crosshair, or -1; and whether gestures are moving it. */
int globeview_candidate(void);

#endif
