/*
 * The globe: a perspective view of the Earth from a camera above
 * (lat, lon), looking straight down, at an altitude in km. Imagery comes
 * from two tile pyramids (tools/make_earth.py: day and night) streamed from
 * the APK on worker threads and blended at the terminator for the time
 * given; the place dots, the crosshair and the atmosphere are drawn on
 * top. Everything here is in real pixels of the globe's viewport.
 */
#ifndef DXG_EARTH_H
#define DXG_EARTH_H

#include "library.h"

#define EARTH_R 6371.0     /* km */
#define GLOBE_ALT_MIN 8.0  /* km */
#define GLOBE_ALT_MAX 60000.0

typedef struct {
    double lat, lon; /* the point straight below, degrees */
    double alt;      /* km above the surface */
} earth_camera;

/* Opens the imagery packs (file paths or APK asset names; either may be
 * missing, and the globe is then plain) and starts the loader threads.
 * Needs a current GL context. */
int earth_init(const char *day_pack, const char *night_pack);
/* After Android has thrown the GL context away (SDL's
 * SDL_EVENT_RENDER_DEVICE_RESET): rebuilds every GL object in the new
 * context. Tiles reload as they are drawn. */
void earth_gl_reset(void);
/* Frees the GPU's copies of every tile but the coarsest (levels 0-2), for
 * when the app goes to the background. They reload as they are drawn. */
void earth_trim(void);
/* Places the Sun for this moment (seconds since 1970, UTC). */
void earth_set_time(double unix_s);
void earth_shutdown(void);
/* Takes the places' positions (call again after the library changes). */
void earth_set_places(const library *lib);

/* Once a frame on the main thread: uploads tiles the loaders finished. */
void earth_update(void);
/* Draws into the current viewport (px_w x px_h). `dot_scale` scales the
 * place dots and crosshair with the screen density. candidate/playing:
 * places to mark, or -1. progress: the crosshair's dwell arc, 0..1. */
void earth_render(const earth_camera *cam, int px_w, int px_h, float dot_scale, int candidate,
                  int playing, double progress);

/* Pixel (x right, y down, from the viewport's top left) to the point on
 * the Earth under it. Returns 1 if the ray hits. */
int earth_unproject(const earth_camera *cam, int px_w, int px_h, double sx, double sy,
                    double *lat, double *lon);
/* The other way: returns 1 if the point is on the visible side. */
int earth_project(const earth_camera *cam, int px_w, int px_h, double lat, double lon,
                  double *sx, double *sy);
/* The visible place nearest the pixel within max_px, or -1. */
int earth_pick(const earth_camera *cam, int px_w, int px_h, double sx, double sy, double max_px);

/* Degrees of latitude per pixel at the centre of the view; short_side is
 * the viewport's shorter side in pixels. */
double earth_deg_per_px(const earth_camera *cam, int short_side);

/* Imagery statistics for the log and About: tiles cached, loading, and
 * the deepest level in the pack. */
void earth_stats(int *cached, int *loading, int *max_level);

#endif
