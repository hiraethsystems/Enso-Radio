/* The globe by touch. See globe_view.h. */
#include "globe_view.h"

#include <math.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "earth.h"
/* core/globe.h's dwell timer: a place plays once it has stayed under the
 * crosshair for a moment. */
#include "globe.h"
#include "log.h"

#define DWELL_S 0.8
#define PICK_DP 22.0    /* around the crosshair */
#define TAP_DP 10.0     /* a finger that moves less than this is a tap */
#define TAP_MS 250      /* ... and lifts within this */
#define DOUBLE_TAP_MS 300
#define DOUBLE_TAP_DP 40.0
#define DOT_TAP_DP 18.0 /* a tap this near a dot plays it */
#define ZOOM_STEP 0.4   /* double-tap: altitude x 0.4; two-finger tap: / 0.4 */
#define MAX_FINGERS 2

static const library *L;
static globeview_hooks H;
static earth_camera cam;
static double alt_target;
static int flying;
static double fly_lat, fly_lon;
static double vlat, vlon; /* coasting, degrees per second */
static globe_dwell dwell;
static int candidate = -1, started;
static int px_w, px_h;
static float dens = 1.0f;
static Uint64 last_ns;
static char last_label[512];

/* Touch state. */
typedef struct {
    SDL_FingerID id;
    double x, y;   /* px now */
    double x0, y0; /* px at touch-down */
} finger;

static finger fingers[MAX_FINGERS];
static int n_fingers;
static int gesture_max;      /* most fingers down at once in this gesture */
static Uint64 gesture_t0;    /* ms, first finger down */
static int gesture_moved;
static int grab_hit;         /* the grabbed point is on the Earth */
static double grab_lat, grab_lon;
static double pinch_d0, pinch_alt0;
static Uint64 last_tap_ms;
static double last_tap_x, last_tap_y;
static double prev_lat, prev_lon;
static Uint64 prev_move_ns;

static double wrap_lon(double lon)
{
    while (lon >= 180.0)
        lon -= 360.0;
    while (lon < -180.0)
        lon += 360.0;
    return lon;
}

static double clampd(double v, double lo, double hi)
{
    return v < lo ? lo : v > hi ? hi : v;
}

void globeview_init(const library *lib, const config *cfg, const globeview_hooks *hooks,
                    const char *day_pack, const char *night_pack)
{
    L = lib;
    H = *hooks;
    /* Starts over Wales, as on Linux, unless a camera was saved. */
    cam.lat = config_get_int(cfg, "globe", "lat_x100", 5200, -8500, 8500) / 100.0;
    cam.lon = config_get_int(cfg, "globe", "lon_x100", -300, -18000, 18000) / 100.0;
    cam.alt = config_get_int(cfg, "globe", "alt_km", 7000, (int)GLOBE_ALT_MIN,
                             (int)GLOBE_ALT_MAX);
    alt_target = cam.alt;
    globe_dwell_init(&dwell);
    if (earth_init(day_pack, night_pack) != 0)
        LOG("globe: could not start the renderer");
    earth_set_places(lib);
}

void globeview_shutdown(void)
{
    earth_shutdown();
}

void globeview_save(config *cfg)
{
    config_set_int(cfg, "globe", "lat_x100", (int)lround(cam.lat * 100));
    config_set_int(cfg, "globe", "lon_x100", (int)lround(cam.lon * 100));
    config_set_int(cfg, "globe", "alt_km", (int)lround(alt_target));
}

void globeview_gl_reset(void)
{
    earth_gl_reset();
}

void globeview_goto(double lat, double lon, double alt)
{
    flying = 0;
    vlat = vlon = 0;
    cam.lat = clampd(lat, -85.0, 85.0);
    cam.lon = wrap_lon(lon);
    if (alt > 0)
        cam.alt = alt_target = clampd(alt, GLOBE_ALT_MIN, GLOBE_ALT_MAX);
}

void globeview_camera(double *lat, double *lon, double *alt)
{
    *lat = cam.lat;
    *lon = cam.lon;
    *alt = cam.alt;
}

int globeview_candidate(void)
{
    return candidate;
}

static int playing_place(void)
{
    int si = H.playing();
    return si >= 0 && si < L->stations.count ? L->stations.items[si].place : -1;
}

/* Plays a place: keeps the station if it is already one of the place's,
 * else the first. */
static void tune(int pl)
{
    if (pl < 0 || pl >= L->n_places || !L->places[pl].n_stations)
        return;
    if (playing_place() == pl)
        return;
    H.play(L->places[pl].stations[0]);
}

static void focus_place(int pl)
{
    if (pl < 0 || pl >= L->n_places)
        return;
    flying = 1;
    fly_lat = L->places[pl].lat;
    fly_lon = L->places[pl].lon;
    vlat = vlon = 0;
    if (alt_target > 3000)
        alt_target = 3000;
    globe_dwell_force(&dwell, pl);
}

/* Moves the camera so the grabbed point is under the pixel again. */
static void follow_grab(double sx, double sy)
{
    int i;
    for (i = 0; i < 4; i++) {
        double la, lo;
        if (!earth_unproject(&cam, px_w, px_h, sx, sy, &la, &lo))
            return;
        cam.lat = clampd(cam.lat + (grab_lat - la), -85.0, 85.0);
        cam.lon = wrap_lon(cam.lon + wrap_lon(grab_lon - lo));
    }
}

/* Grabs the point under (sx, sy), for a drag from there. */
static void grab(double sx, double sy)
{
    grab_hit = earth_unproject(&cam, px_w, px_h, sx, sy, &grab_lat, &grab_lon);
}

/* The point the gesture follows: the finger, or the middle of two. */
static void focus_point(double *x, double *y)
{
    if (n_fingers >= 2) {
        *x = (fingers[0].x + fingers[1].x) / 2;
        *y = (fingers[0].y + fingers[1].y) / 2;
    } else {
        *x = fingers[0].x;
        *y = fingers[0].y;
    }
}

static double finger_dist(void)
{
    double dx = fingers[0].x - fingers[1].x, dy = fingers[0].y - fingers[1].y;
    return sqrt(dx * dx + dy * dy);
}

/* Starts following the current fingers afresh (after one is added or
 * lifted), so the globe doesn't jump. */
static void regrab(void)
{
    double x, y;
    if (n_fingers == 0)
        return;
    focus_point(&x, &y);
    grab(x, y);
    if (n_fingers >= 2) {
        pinch_d0 = finger_dist();
        pinch_alt0 = cam.alt;
    }
}

static int find_finger(SDL_FingerID id)
{
    int i;
    for (i = 0; i < n_fingers; i++)
        if (fingers[i].id == id)
            return i;
    return -1;
}

static void on_tap(double x, double y, Uint64 now_ms)
{
    double la, lo;
    if (last_tap_ms && now_ms - last_tap_ms <= DOUBLE_TAP_MS &&
        fabs(x - last_tap_x) + fabs(y - last_tap_y) <= DOUBLE_TAP_DP * dens) {
        /* Double-tap: fly there and zoom in. */
        last_tap_ms = 0;
        if (earth_unproject(&cam, px_w, px_h, x, y, &la, &lo)) {
            flying = 1;
            fly_lat = la;
            fly_lon = lo;
        }
        alt_target = fmax(alt_target * ZOOM_STEP, GLOBE_ALT_MIN);
        LOG("globe: double-tap, zoom to %.0f km", alt_target);
        return;
    }
    last_tap_ms = now_ms;
    last_tap_x = x;
    last_tap_y = y;
    /* A tap on a dot turns to it and plays it. */
    {
        int p = earth_pick(&cam, px_w, px_h, x, y, DOT_TAP_DP * dens);
        if (p >= 0) {
            LOG("globe: tapped %s, %s", L->places[p].name, L->places[p].country);
            focus_place(p);
            tune(p);
            if (H.tick)
                H.tick();
        }
    }
}

void globeview_event(const SDL_Event *e, int w, int h)
{
    Uint64 now_ms = SDL_GetTicks();
    double x, y;
    int i;
    if (e->type != SDL_EVENT_FINGER_DOWN && e->type != SDL_EVENT_FINGER_MOTION &&
        e->type != SDL_EVENT_FINGER_UP && e->type != SDL_EVENT_FINGER_CANCELED)
        return;
    px_w = w;
    px_h = h;
    x = e->tfinger.x * w;
    y = e->tfinger.y * h;
    i = find_finger(e->tfinger.fingerID);

    switch (e->type) {
    case SDL_EVENT_FINGER_DOWN:
        if (i >= 0 || n_fingers >= MAX_FINGERS)
            return; /* a third finger is ignored */
        if (n_fingers == 0) {
            gesture_max = 0;
            gesture_t0 = now_ms;
            gesture_moved = 0;
            flying = 0;
            vlat = vlon = 0;
            prev_lat = cam.lat;
            prev_lon = cam.lon;
            prev_move_ns = SDL_GetTicksNS();
        }
        fingers[n_fingers].id = e->tfinger.fingerID;
        fingers[n_fingers].x = fingers[n_fingers].x0 = x;
        fingers[n_fingers].y = fingers[n_fingers].y0 = y;
        n_fingers++;
        if (n_fingers > gesture_max)
            gesture_max = n_fingers;
        alt_target = cam.alt; /* a touch stops any zoom in progress */
        regrab();
        break;

    case SDL_EVENT_FINGER_MOTION:
        if (i < 0)
            return;
        fingers[i].x = x;
        fingers[i].y = y;
        if (fabs(x - fingers[i].x0) + fabs(y - fingers[i].y0) > TAP_DP * dens)
            gesture_moved = 1;
        if (!gesture_moved)
            return;
        if (n_fingers >= 2 && pinch_d0 > 1) {
            double d = finger_dist();
            if (d > 1) {
                cam.alt = alt_target =
                    clampd(pinch_alt0 * pinch_d0 / d, GLOBE_ALT_MIN, GLOBE_ALT_MAX);
            }
        }
        focus_point(&x, &y);
        if (grab_hit) {
            follow_grab(x, y);
        } else {
            /* Grabbed the sky: grab again where the finger is now. */
            grab(x, y);
        }
        {
            /* Smoothed velocity for the fling. */
            Uint64 ns = SDL_GetTicksNS();
            double dt = (ns - prev_move_ns) / 1e9;
            if (dt > 0.001) {
                double nl = (cam.lat - prev_lat) / dt, no = wrap_lon(cam.lon - prev_lon) / dt;
                vlat = vlat * 0.5 + nl * 0.5;
                vlon = vlon * 0.5 + no * 0.5;
                prev_lat = cam.lat;
                prev_lon = cam.lon;
                prev_move_ns = ns;
            }
        }
        break;

    case SDL_EVENT_FINGER_UP:
    case SDL_EVENT_FINGER_CANCELED:
        if (i < 0)
            return;
        fingers[i] = fingers[n_fingers - 1];
        n_fingers--;
        if (n_fingers > 0) {
            /* Pinch to drag: carry on with the other finger. */
            vlat = vlon = 0;
            regrab();
            return;
        }
        if (e->type == SDL_EVENT_FINGER_CANCELED) {
            vlat = vlon = 0;
            return;
        }
        if (!gesture_moved && now_ms - gesture_t0 <= TAP_MS + (gesture_max > 1 ? 100 : 0)) {
            vlat = vlon = 0;
            if (gesture_max >= 2) {
                /* Two-finger tap: zoom out. */
                alt_target = fmin(alt_target / ZOOM_STEP, GLOBE_ALT_MAX);
                last_tap_ms = 0;
                LOG("globe: two-finger tap, zoom to %.0f km", alt_target);
            } else {
                on_tap(x, y, now_ms);
            }
            return;
        }
        /* A finger held still before lifting doesn't fling. */
        if (gesture_max > 1 || (SDL_GetTicksNS() - prev_move_ns) > 80000000ull)
            vlat = vlon = 0;
        break;
    }
}

static void motion(double dt)
{
    /* Zoom eases in log space, so each step feels the same at any height. */
    double k = 1.0 - exp(-dt * 10.0);
    if (n_fingers < 2) {
        cam.alt = exp(log(cam.alt) + (log(alt_target) - log(cam.alt)) * k);
        if (fabs(cam.alt - alt_target) < 0.01)
            cam.alt = alt_target;
    }
    if (flying) {
        double r = 1.0 - exp(-dt * 6.0), dl = wrap_lon(fly_lon - cam.lon);
        cam.lat += (fly_lat - cam.lat) * r;
        cam.lon = wrap_lon(cam.lon + dl * r);
        if (fabs(fly_lat - cam.lat) < 1e-4 && fabs(dl) < 1e-4)
            flying = 0;
    } else if (n_fingers == 0 && (vlat != 0 || vlon != 0)) {
        int side = px_w < px_h ? px_w : px_h;
        double stop = earth_deg_per_px(&cam, side > 0 ? side : 600) * 3.0;
        cam.lat = clampd(cam.lat + vlat * dt, -85.0, 85.0);
        cam.lon = wrap_lon(cam.lon + vlon * dt);
        vlat *= exp(-dt * 3.0);
        vlon *= exp(-dt * 3.0);
        if (fabs(vlat) < stop && fabs(vlon) < stop)
            vlat = vlon = 0;
    }
}

/* The label under the crosshair: the place being tuned, or the one
 * playing. Sent only when it changes. */
static void update_label(void)
{
    int pl = candidate >= 0 ? candidate : playing_place(), si = H.playing();
    char place_s[300], detail[300], both[620];
    if (pl >= 0) {
        const place *p = &L->places[pl];
        snprintf(place_s, sizeof(place_s), "%s%s%s", p->name, p->country[0] ? ", " : "",
                 p->country);
        if (si >= 0 && L->stations.items[si].place == pl)
            snprintf(detail, sizeof(detail), "%s", L->stations.items[si].name);
        else
            snprintf(detail, sizeof(detail), "%d station%s", p->n_stations,
                     p->n_stations == 1 ? "" : "s");
    } else {
        place_s[0] = 0;
        detail[0] = 0;
    }
    snprintf(both, sizeof(both), "%s\n%s", place_s, detail);
    if (strcmp(both, last_label) != 0) {
        snprintf(last_label, sizeof(last_label), "%s", both);
        if (H.label)
            H.label(place_s, detail);
    }
}

void globeview_frame(int w, int h, float density, double unix_s)
{
    Uint64 ns = SDL_GetTicksNS();
    double dt = last_ns ? (ns - last_ns) / 1e9 : 0, now = ns / 1e9, progress = 0;
    if (dt > 0.1)
        dt = 0.1;
    last_ns = ns;
    px_w = w;
    px_h = h;
    dens = density > 0 ? density : 1.0f;
    if (w < 1 || h < 1)
        return;

    motion(dt);
    candidate = earth_pick(&cam, w, h, w / 2.0, h / 2.0, PICK_DP * dens);
    if (!started) {
        /* Don't start playing just because the app opened over a place. */
        globe_dwell_force(&dwell, candidate);
        started = 1;
    }
    {
        /* While a finger is down the timer follows the candidate but
         * doesn't fire; it tunes once the globe is let go. */
        int t = globe_dwell_update(&dwell, candidate, now, n_fingers ? 1e9 : DWELL_S);
        if (t >= 0) {
            LOG("globe: tuned to %s, %s", L->places[t].name, L->places[t].country);
            tune(t);
            if (H.tick)
                H.tick();
        }
    }
    if (candidate >= 0 && candidate != playing_place())
        progress = globe_dwell_progress(&dwell, now, DWELL_S);
    update_label();

    earth_update();
    earth_set_time(unix_s > 0 ? unix_s : (double)time(NULL));
    earth_render(&cam, w, h, dens, candidate, playing_place(), progress >= 1 ? 0 : progress);
}
