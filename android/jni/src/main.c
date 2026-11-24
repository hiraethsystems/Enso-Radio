/*
 * DX Garden for Android, native side.
 *
 * A2: the globe (OpenGL ES 3) with the bundled station list. Hold the
 * crosshair over a place and it plays.
 * A3: it keeps playing in the background and with the screen off, with
 * the media session (Playback.kt) for the notification, lock screen and
 * headset buttons. In the background nothing is drawn: the loop only keeps
 * the player going and answers commands. The log goes to
 * log-<version>-b<build>.txt in the app's external files folder and to
 * logcat (tag DXGarden).
 *
 * Test options, from intent extras in debuggable builds (MainActivity):
 *   --play URL        play this stream and nothing else (A1's test)
 *   --seconds N       stop after N seconds, logging the player each second,
 *                     and close the app ("result: played" in the log)
 *   --goto "LAT LON ALT"   start the camera here (ALT in km)
 *   --time UNIX       fix the Sun at this moment (seconds since 1970 UTC)
 *   --screenshot FILE --frames N   draw N frames, save the last as a BMP
 *                     in the data folder, and quit
 *   --script "STEPS"  ';'-separated, one step a frame:
 *                     wait N (frames), sleep MS, goto LAT LON ALT,
 *                     camera (logs it), shot FILE, glreset (rebuilds the
 *                     globe's GL objects as after a lost context),
 *                     fdown|fmove|fup ID X Y (a finger, window pixels),
 *                     trace N (log the camera for N frames), probe X Y (the
 *                     point under a pixel), stats (tile cache), quit
 *   --verbose         (accepted; the log always goes to logcat)
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <GLES3/gl3.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <curl/curl.h>

#include "app.h"
#include "bridge.h"
#include "config.h"
#include "earth.h"
#include "globe_view.h"
#include "library.h"
#include "load_report.h"
#include "log.h"
#include "player.h"
#include "station_source.h"
#include "userlist.h"

#define RING_BYTES (2 * 1024 * 1024) /* about 2 minutes at 128 kbps */
#define PREBUFFER_BYTES (64 * 1024)  /* about 4 s at 128 kbps */

static char *dir;           /* the data folder, ending in '/' */
static library lib;
static config cfg;
static int cur_station = -1;
static double fixed_time;   /* --time, or 0 for now */
static const char *test_url; /* --play */
static int paused;          /* paused by the user: cur_station resumes on play */
static int in_background;   /* not on screen: no drawing (GL is not ours) */
static SDL_AtomicInt lib_ready; /* the library has loaded */
static SDL_AtomicInt globe_left; /* px on the left covered by the panel */
static size_t prebuffer_bytes = PREBUFFER_BYTES;

/* Commands from the interface, queued by any thread, run by the loop. */
#define CMD_QUEUE 32
static SDL_Mutex *cmd_lock;
static struct {
    int cmd, arg;
} cmd_queue[CMD_QUEUE];
static int cmd_count;

/* favourites.txt and recent.txt, by stream URL. */
static SDL_Mutex *lists_lock;
static userlist favs, recent;
/* States for Playback.kt (its state constants). */
enum { PB_STOPPED, PB_CONNECTING, PB_PLAYING, PB_PAUSED, PB_ERROR };

static const char *state_name(player_state s)
{
    switch (s) {
    case PLAYER_IDLE: return "idle";
    case PLAYER_CONNECTING: return "connecting";
    case PLAYER_BUFFERING: return "buffering";
    case PLAYER_PLAYING: return "playing";
    case PLAYER_STOPPING: return "stopping";
    case PLAYER_ERROR: return "error";
    case PLAYER_RECONNECTING: return "reconnecting";
    }
    return "?";
}

/* The app's external files folder (Android/data/<id>/files/), which a
 * computer can reach over USB. Ends in '/'. */
static char *data_dir(void)
{
    const char *ext = SDL_GetAndroidExternalStoragePath();
    char *d;
    if (!ext) {
        /* Shouldn't happen on Android 13+; fall back to internal storage. */
        ext = SDL_GetAndroidInternalStoragePath();
    }
    if (!ext)
        return SDL_strdup("./");
    SDL_CreateDirectory(ext);
    SDL_asprintf(&d, "%s/", ext);
    return d;
}

static char *data_path(const char *name)
{
    char *p;
    SDL_asprintf(&p, "%s%s", dir, name);
    return p;
}

static void open_log(void)
{
    char path[1024];
    char prev[1024];
    snprintf(path, sizeof(path), "%slog-%s-b%s.txt", dir, DXG_VERSION, DXG_BUILD);
    snprintf(prev, sizeof(prev), "%slog-%s-b%s-previous.txt", dir, DXG_VERSION, DXG_BUILD);
    remove(prev);
    rename(path, prev);
    log_open(path);
}

/* One status line, as the Linux --play mode prints it. */
static void log_status(const player_status *st, int second)
{
    LOG("status %3ds: %s%s%s, buffer %u%%, %s %ld Hz %d ch %d kbps, decode %u%%, "
        "underruns %u, frames out %llu, title '%s'",
        second, state_name(st->state), st->state == PLAYER_ERROR ? ": " : "",
        st->state == PLAYER_ERROR ? st->error : "", st->fill_pct, st->codec[0] ? st->codec : "-",
        st->rate, st->channels, st->bitrate_kbps, st->decode_load_pct, st->underruns,
        (unsigned long long)st->frames_out, st->title);
}

/* ---- the user's files ---- */

/* First run: the bundled list (an APK asset) becomes the user's
 * stations.txt. An existing one is never touched. */
static void first_run_copy(void)
{
    char *path = data_path("stations.txt");
    SDL_PathInfo info;
    if (!SDL_GetPathInfo(path, &info)) {
        size_t len = 0;
        void *buf = SDL_LoadFile("stations.txt", &len);
        if (!buf) {
            LOG("stations.txt: no bundled list in the APK: %s", SDL_GetError());
        } else {
            char *tmp = data_path("stations.txt.new");
            if (SDL_SaveFile(tmp, buf, len) && rename(tmp, path) == 0)
                LOG("stations.txt: first run, copied the bundled list (%zu bytes)", len);
            else
                LOG("stations.txt: could not write %s", path);
            SDL_free(tmp);
            SDL_free(buf);
        }
    }
    SDL_free(path);
}

static void load_library(void)
{
    char *path = data_path("stations.txt");
    station_source *src = stations_file_source_new(path);
    station_list all;
    load_report rep;
    Uint64 t0 = SDL_GetTicks();
    station_list_init(&all);
    report_init(&rep);
    if (src) {
        int n = src->load(src, &all, &rep);
        LOG("library: %d stations from %s", n, src->name);
        station_source_destroy(src);
    }
    if (library_build(&lib, &all, &rep) != 0)
        LOG("library: out of memory while building");
    LOG("library: %d stations, %d places, %d countries, %d genres, %d unplaced, %llu ms",
        lib.stations.count, lib.n_places, lib.n_countries, lib.n_genres, lib.n_unplaced,
        (unsigned long long)(SDL_GetTicks() - t0));
    report_free(&rep);
    SDL_free(path);
}

static void load_config(void)
{
    char *path = data_path("config.ini");
    size_t len = 0;
    char *buf = SDL_LoadFile(path, &len);
    config_init(&cfg);
    if (buf) {
        load_report rep;
        report_init(&rep);
        config_parse(&cfg, buf, len, &rep);
        report_free(&rep);
        SDL_free(buf);
    }
    SDL_free(path);
}

static void save_config(void)
{
    char *path = data_path("config.ini"), *tmp = data_path("config.ini.new");
    char *text;
    globeview_save(&cfg);
    text = config_serialize(&cfg);
    if (text && SDL_SaveFile(tmp, text, strlen(text)) && rename(tmp, path) == 0)
        LOG("config: saved");
    else
        LOG("config: could not save %s", path);
    free(text);
    SDL_free(path);
    SDL_free(tmp);
}

/* ---- favourites and recent ---- */

static void load_list(userlist *l, const char *name)
{
    char *path = data_path(name);
    size_t len = 0;
    char *buf = SDL_LoadFile(path, &len);
    userlist_init(l);
    if (buf) {
        userlist_parse(l, buf, len);
        SDL_free(buf);
    }
    SDL_free(path);
}

/* Called with lists_lock held. */
static void save_list(const userlist *l, const char *name, const char *header)
{
    char *path = data_path(name), *tmp;
    char *text = userlist_serialize(l, header);
    SDL_asprintf(&tmp, "%s.new", path);
    if (!(text && SDL_SaveFile(tmp, text, strlen(text)) && rename(tmp, path) == 0))
        LOG("lists: could not save %s", path);
    free(text);
    SDL_free(tmp);
    SDL_free(path);
}

const library *app_library(void)
{
    return SDL_GetAtomicInt(&lib_ready) ? &lib : NULL;
}

void app_set_globe_left(int px)
{
    SDL_SetAtomicInt(&globe_left, px > 0 ? px : 0);
}

int app_list(int which, int *out, int max)
{
    const userlist *l = which ? &recent : &favs;
    int i, n = 0;
    if (!app_library())
        return 0;
    SDL_LockMutex(lists_lock);
    for (i = 0; i < l->count && n < max; i++) {
        int si = library_find_url(&lib, l->urls[i]);
        if (si >= 0)
            out[n++] = si;
    }
    SDL_UnlockMutex(lists_lock);
    return n;
}

int app_list_count(int which)
{
    int n;
    SDL_LockMutex(lists_lock);
    n = which ? recent.count : favs.count;
    SDL_UnlockMutex(lists_lock);
    return n;
}

int app_is_favourite(int si)
{
    int r;
    if (!app_library() || si < 0 || si >= lib.stations.count)
        return 0;
    SDL_LockMutex(lists_lock);
    r = userlist_index(&favs, lib.stations.items[si].url) >= 0;
    SDL_UnlockMutex(lists_lock);
    return r;
}

int app_toggle_favourite(int si)
{
    int r;
    if (!app_library() || si < 0 || si >= lib.stations.count)
        return 0;
    SDL_LockMutex(lists_lock);
    r = userlist_toggle(&favs, lib.stations.items[si].url);
    save_list(&favs, "favourites.txt", FAVOURITES_HEADER);
    SDL_UnlockMutex(lists_lock);
    LOG("favourites: %s %s", r ? "added" : "removed", lib.stations.items[si].name);
    return r;
}

/* ---- playing ---- */

static void play_station(int si)
{
    if (si < 0 || si >= lib.stations.count)
        return;
    cur_station = si;
    LOG("play: %s <%s>", lib.stations.items[si].name, lib.stations.items[si].url);
    player_start(lib.stations.items[si].url, prebuffer_bytes);
    SDL_LockMutex(lists_lock);
    userlist_push_front(&recent, lib.stations.items[si].url, RECENT_MAX);
    save_list(&recent, "recent.txt", RECENT_HEADER);
    SDL_UnlockMutex(lists_lock);
}

static int playing_station(void)
{
    return cur_station;
}

/* From any thread (Playback.kt, on Android's main thread): queue a command
 * for the loop, which takes it within 100 ms even in the background. */
JNIEXPORT void JNICALL Java_systems_hiraeth_dxgarden_Playback_nativeCommand(JNIEnv *env, jclass cls,
                                                                            jint cmd)
{
    (void)env;
    (void)cls;
    app_command(cmd, 0);
}

void app_command(int cmd, int arg)
{
    if (!cmd_lock)
        return;
    SDL_LockMutex(cmd_lock);
    if (cmd_count < CMD_QUEUE) {
        cmd_queue[cmd_count].cmd = cmd;
        cmd_queue[cmd_count].arg = arg;
        cmd_count++;
    }
    SDL_UnlockMutex(cmd_lock);
}

static int next_command(int *arg)
{
    int cmd = 0;
    SDL_LockMutex(cmd_lock);
    if (cmd_count > 0) {
        cmd = cmd_queue[0].cmd;
        *arg = cmd_queue[0].arg;
        memmove(cmd_queue, cmd_queue + 1, (size_t)--cmd_count * sizeof(cmd_queue[0]));
    }
    SDL_UnlockMutex(cmd_lock);
    return cmd;
}

/* The next (step 1) or previous (-1) station at the playing station's place,
 * round in a circle. */
static int step_station(int step)
{
    const station *s;
    const place *pl;
    int i;
    if (cur_station < 0 || cur_station >= lib.stations.count)
        return -1;
    s = &lib.stations.items[cur_station];
    if (s->place < 0 || s->place >= lib.n_places)
        return -1;
    pl = &lib.places[s->place];
    for (i = 0; i < pl->n_stations; i++)
        if (pl->stations[i] == cur_station)
            return pl->stations[(i + step + pl->n_stations) % pl->n_stations];
    return -1;
}

static void command(int cmd, int arg)
{
    player_status st;
    player_get_status(&st);
    switch (cmd) {
    case CMD_PLAY:
        LOG("command: play");
        if (st.state != PLAYER_IDLE && st.state != PLAYER_ERROR && st.state != PLAYER_STOPPING)
            break;
        paused = 0;
        if (cur_station >= 0)
            play_station(cur_station);
        else if (test_url)
            player_start(test_url, prebuffer_bytes);
        else if (globeview_candidate() >= 0 && lib.places[globeview_candidate()].n_stations)
            play_station(lib.places[globeview_candidate()].stations[0]);
        break;
    case CMD_PAUSE:
        LOG("command: pause");
        /* A live stream can't wait: pausing stops it, playing again
         * reconnects to the same station. */
        if (cur_station >= 0 || test_url) {
            paused = 1;
            player_stop();
        }
        break;
    case CMD_STOP:
        LOG("command: stop");
        paused = 0;
        cur_station = -1;
        test_url = NULL;
        player_stop();
        break;
    case CMD_NEXT:
    case CMD_PREVIOUS: {
        int si = step_station(cmd == CMD_NEXT ? 1 : -1);
        LOG("command: %s", cmd == CMD_NEXT ? "next" : "previous");
        if (si >= 0) {
            paused = 0;
            play_station(si);
        }
        break;
    }
    case CMD_PLAY_STATION:
        if (arg >= 0 && arg < lib.stations.count) {
            LOG("command: play station %d", arg);
            paused = 0;
            test_url = NULL;
            play_station(arg);
            globeview_show_place(lib.stations.items[arg].place, 0);
        }
        break;
    case CMD_SHOW_PLACE:
        if (arg >= 0 && arg < lib.n_places) {
            LOG("command: show place %d", arg);
            paused = 0;
            test_url = NULL;
            globeview_show_place(arg, 1);
        }
        break;
    case CMD_PREBUFFER:
        if (arg >= CONFIG_PREBUFFER_KB_MIN && arg <= CONFIG_PREBUFFER_KB_MAX) {
            prebuffer_bytes = (size_t)arg * 1024;
            config_set_int(&cfg, "player", "prebuffer_kb", arg);
            player_set_prebuffer(prebuffer_bytes);
            LOG("command: prebuffer %d KB", arg);
        }
        break;
    default:
        break;
    }
}

/* Tells Playback.kt when what it shows changes: the state, the station,
 * the title playing (or the error) and the place. */
static void report_playback(const player_status *st)
{
    static int last_state = -1, last_station = -2;
    static char last[1024];
    char name[256] = "", detail[256] = "", where[256] = "", key[1024];
    int state;
    if (cur_station >= 0 && cur_station < lib.stations.count) {
        const station *s = &lib.stations.items[cur_station];
        SDL_strlcpy(name, s->name, sizeof(name));
        if (s->place >= 0 && s->place < lib.n_places) {
            const place *pl = &lib.places[s->place];
            if (pl->country[0] && !pl->country_wide)
                SDL_snprintf(where, sizeof(where), "%s, %s", pl->name, pl->country);
            else
                SDL_strlcpy(where, pl->country[0] ? pl->country : pl->name, sizeof(where));
        }
    } else if (test_url) {
        SDL_strlcpy(name, st->icy_name[0] ? st->icy_name : test_url, sizeof(name));
    }
    switch (st->state) {
    case PLAYER_PLAYING:
        state = PB_PLAYING;
        break;
    case PLAYER_CONNECTING:
    case PLAYER_BUFFERING:
    case PLAYER_RECONNECTING:
        state = PB_CONNECTING;
        break;
    case PLAYER_ERROR:
        state = PB_ERROR;
        break;
    case PLAYER_STOPPING:
        /* Between two stations (next, or a new place) the old stream stops
         * first: still connecting as far as the notification goes. */
        state = paused ? PB_PAUSED : (cur_station >= 0 || test_url) ? PB_CONNECTING : PB_STOPPED;
        break;
    default:
        state = paused ? PB_PAUSED : PB_STOPPED;
        break;
    }
    if (!name[0])
        state = PB_STOPPED;
    if (state == PB_ERROR)
        SDL_strlcpy(detail, st->error, sizeof(detail));
    else if (state == PB_PLAYING)
        SDL_strlcpy(detail, st->title, sizeof(detail));
    SDL_snprintf(key, sizeof(key), "%s\x1f%s\x1f%s", name, detail, where);
    if (state == last_state && cur_station == last_station && strcmp(key, last) == 0)
        return;
    last_state = state;
    last_station = cur_station;
    SDL_strlcpy(last, key, sizeof(last));
    bridge_playback(state, state == PB_STOPPED ? -1 : cur_station, name, detail, where);
}

/* ---- the app's lifecycle ---- */

/* SDL 3 doesn't queue the background and foreground events: they go only to
 * event watchers, called right then, before the app is paused. Settings
 * are saved there, as Android may end the process while it is away. */
static bool SDLCALL lifecycle(void *ud, SDL_Event *e)
{
    (void)ud;
    switch (e->type) {
    case SDL_EVENT_WILL_ENTER_BACKGROUND:
        LOG("app: going to the background");
        in_background = 1;
        save_config();
        /* The GL context is still current here (SDL lets it go after). */
        earth_trim();
        break;
    case SDL_EVENT_DID_ENTER_FOREGROUND:
        /* SDL has made the GL context current again by now. */
        LOG("app: back in the foreground");
        in_background = 0;
        break;
    case SDL_EVENT_LOW_MEMORY:
        LOG("app: low memory");
        break;
    default:
        break;
    }
    return true;
}

/* ---- screenshots and scripts ---- */

static void save_screenshot(SDL_Window *win, const char *name)
{
    int w, h, y;
    SDL_Surface *surf;
    unsigned char *tmp;
    char *path = name[0] == '/' ? SDL_strdup(name) : data_path(name);
    SDL_GetWindowSizeInPixels(win, &w, &h);
    surf = SDL_CreateSurface(w, h, SDL_PIXELFORMAT_ABGR8888);
    tmp = malloc((size_t)w * 4);
    if (surf && tmp) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, surf->pixels);
        /* GL rows run bottom-up. */
        for (y = 0; y < h / 2; y++) {
            unsigned char *a = (unsigned char *)surf->pixels + (size_t)y * surf->pitch;
            unsigned char *b = (unsigned char *)surf->pixels + (size_t)(h - 1 - y) * surf->pitch;
            memcpy(tmp, a, (size_t)w * 4);
            memcpy(a, b, (size_t)w * 4);
            memcpy(b, tmp, (size_t)w * 4);
        }
        if (SDL_SaveBMP(surf, path))
            LOG("screenshot: %s (%dx%d)", path, w, h);
        else
            LOG("screenshot: could not save %s: %s", path, SDL_GetError());
    }
    free(tmp);
    if (surf)
        SDL_DestroySurface(surf);
    SDL_free(path);
}

static int trace_frames;

static void push_finger(SDL_Window *win, Uint32 type, int id, double x, double y)
{
    SDL_Event e;
    int w, h;
    SDL_GetWindowSizeInPixels(win, &w, &h);
    SDL_zero(e);
    e.type = type;
    e.tfinger.touchID = 1;
    e.tfinger.fingerID = (SDL_FingerID)id;
    e.tfinger.x = (float)(x / w);
    e.tfinger.y = (float)(y / h);
    e.tfinger.pressure = 1.0f;
    e.tfinger.windowID = SDL_GetWindowID(win);
    SDL_PushEvent(&e);
}

/* Runs script steps up to the next wait. Returns 0 on "quit". */
static int script_step(SDL_Window *win, char **cursor, int *wait)
{
    char step[256];
    while (**cursor) {
        char *end = strchr(*cursor, ';');
        size_t n = end ? (size_t)(end - *cursor) : strlen(*cursor);
        double a = 0, b = 0, c = 0;
        char arg[200] = "";
        if (*wait > 0) {
            (*wait)--;
            return 1;
        }
        if (n >= sizeof(step))
            n = sizeof(step) - 1;
        memcpy(step, *cursor, n);
        step[n] = '\0';
        *cursor += n + (end ? 1 : 0);
        while (**cursor == ' ')
            (*cursor)++;
        if (sscanf(step, " wait %lf", &a) == 1) {
            *wait = (int)a;
            return 1;
        } else if (sscanf(step, " sleep %lf", &a) == 1) {
            SDL_Delay((Uint32)a);
        } else if (sscanf(step, " goto %lf %lf %lf", &a, &b, &c) == 3) {
            globeview_goto(a, b, c);
        } else if (strstr(step, "camera")) {
            globeview_camera(&a, &b, &c);
            LOG("script: camera %.4f %.4f %.1f km, candidate %d", a, b, c,
                globeview_candidate());
        } else if (sscanf(step, " shot %199s", arg) == 1) {
            save_screenshot(win, arg);
        } else if (sscanf(step, " fdown %lf %lf %lf", &a, &b, &c) == 3) {
            push_finger(win, SDL_EVENT_FINGER_DOWN, (int)a, b, c);
        } else if (sscanf(step, " fmove %lf %lf %lf", &a, &b, &c) == 3) {
            push_finger(win, SDL_EVENT_FINGER_MOTION, (int)a, b, c);
        } else if (sscanf(step, " fup %lf %lf %lf", &a, &b, &c) == 3) {
            push_finger(win, SDL_EVENT_FINGER_UP, (int)a, b, c);
        } else if (sscanf(step, " probe %lf %lf", &a, &b) == 2) {
            int w, h;
            double la, lo;
            SDL_GetWindowSizeInPixels(win, &w, &h);
            if (globeview_probe(w, h, a, b, &la, &lo))
                LOG("script: probe %.0f %.0f -> %.4f %.4f", a, b, la, lo);
            else
                LOG("script: probe %.0f %.0f -> sky", a, b);
        } else if (strstr(step, "stats")) {
            int cached, loading, maxl;
            earth_stats(&cached, &loading, &maxl);
            LOG("script: tiles %d cached, %d loading, %d drawn", cached, loading, earth_drawn());
        } else if (sscanf(step, " trace %lf", &a) == 1) {
            trace_frames = (int)a;
        } else if (strstr(step, "glreset")) {
            globeview_gl_reset();
        } else if (strstr(step, "quit")) {
            return 0;
        }
    }
    return 1;
}

/* ---- main ---- */

int main(int argc, char *argv[])
{
    const char *url = NULL, *shot = NULL, *go = NULL;
    char *script = NULL;
    int seconds = 0, frames = 0, i, played = 0, running = 1, rc = 0, script_wait = 0, frame = 0;
    SDL_Window *win;
    SDL_GLContext gl;
    player_status st;
    player_state last_state = PLAYER_IDLE;
    Uint64 start_ms, next_status_ms;
    globeview_hooks hooks;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--play") == 0 && i + 1 < argc)
            url = argv[++i];
        else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc)
            seconds = atoi(argv[++i]);
        else if (strcmp(argv[i], "--screenshot") == 0 && i + 1 < argc)
            shot = argv[++i];
        else if (strcmp(argv[i], "--frames") == 0 && i + 1 < argc)
            frames = atoi(argv[++i]);
        else if (strcmp(argv[i], "--script") == 0 && i + 1 < argc)
            script = argv[++i];
        else if (strcmp(argv[i], "--goto") == 0 && i + 1 < argc)
            go = argv[++i];
        else if (strcmp(argv[i], "--time") == 0 && i + 1 < argc)
            fixed_time = atof(argv[++i]);
    }
    if (shot && frames <= 0)
        frames = 60;

    /* Touches come as finger events only; no pretend mouse. */
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    /* Keep running when the app is paused (in the background, or the screen
     * off), so the player goes on; audio isn't paused either. */
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
    SDL_SetAppMetadata("DX Garden", DXG_VERSION, "systems.hiraeth.dxgarden");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_EVENTS)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    cmd_lock = SDL_CreateMutex();
    lists_lock = SDL_CreateMutex();
    dir = data_dir();
    open_log();
    LOG("DX Garden %s (build %s), Android (API %d), SDL %d.%d.%d, data in %s", DXG_VERSION,
        DXG_BUILD, SDL_GetAndroidSDKVersion(), SDL_VERSIONNUM_MAJOR(SDL_GetVersion()),
        SDL_VERSIONNUM_MINOR(SDL_GetVersion()), SDL_VERSIONNUM_MICRO(SDL_GetVersion()), dir);
    LOG("audio: driver %s", SDL_GetCurrentAudioDriver());
    {
        const curl_version_info_data *v = curl_version_info(CURLVERSION_NOW);
        LOG("net: curl %s, %s", v->version, v->ssl_version ? v->ssl_version : "no TLS");
    }

    if (curl_global_init(CURL_GLOBAL_ALL) != CURLE_OK) {
        LOG("net: curl_global_init failed");
        return 1;
    }
    if (player_init(RING_BYTES) != 0) {
        LOG("player: could not start");
        return 1;
    }

    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 0);
    /* Not SDL_WINDOW_FULLSCREEN: that hides the system bars (immersive
     * mode). The app is edge to edge instead, with the bars over the globe. */
    win = SDL_CreateWindow("DX Garden", 0, 0, SDL_WINDOW_OPENGL | SDL_WINDOW_HIGH_PIXEL_DENSITY);
    gl = win ? SDL_GL_CreateContext(win) : NULL;
    if (!gl) {
        LOG("window or GL context failed: %s", SDL_GetError());
        return 1;
    }
    SDL_GL_SetSwapInterval(1);

    load_config();
    prebuffer_bytes = (size_t)config_get_int(&cfg, "player", "prebuffer_kb",
                                             CONFIG_PREBUFFER_KB_DEFAULT, CONFIG_PREBUFFER_KB_MIN,
                                             CONFIG_PREBUFFER_KB_MAX) * 1024;
    first_run_copy();
    load_library();
    load_list(&favs, "favourites.txt");
    load_list(&recent, "recent.txt");
    LOG("lists: %d favourites, %d recent", favs.count, recent.count);
    hooks.play = play_station;
    hooks.playing = playing_station;
    hooks.label = bridge_globe_label;
    hooks.tick = bridge_haptic_tick;
    globeview_init(&lib, &cfg, &hooks, "earth-day.pack", "earth-night.pack");
    SDL_AddEventWatch(lifecycle, NULL);
    SDL_SetAtomicInt(&lib_ready, 1);
    bridge_library_ready(lib.stations.count);
    if (go) {
        double a, b, c;
        if (sscanf(go, "%lf %lf %lf", &a, &b, &c) == 3)
            globeview_goto(a, b, c);
    }

    if (url) {
        LOG("play: %s%s", url, seconds ? " (test run)" : "");
        test_url = url;
        player_start(url, prebuffer_bytes);
    }
    start_ms = SDL_GetTicks();
    next_status_ms = start_ms + (seconds ? 1000 : 5000);

    while (running) {
        SDL_Event e;
        Uint64 now;
        int w, h, gx, gw;
        SDL_GetWindowSizeInPixels(win, &w, &h);
        /* The globe's part of the window: all of it, less the side panel in
         * landscape, so the crosshair is in the middle of what you see. */
        gx = SDL_GetAtomicInt(&globe_left);
        if (gx > w / 2)
            gx = w / 2;
        gw = w - gx;
        /* In the background, wait for events (or 100 ms) instead of
         * drawing: enough to keep the player and its reconnects going. */
        if (in_background)
            SDL_Delay(100);
        while (SDL_PollEvent(&e)) {
            switch (e.type) {
            case SDL_EVENT_QUIT:
            case SDL_EVENT_TERMINATING:
                running = 0;
                break;
            case SDL_EVENT_RENDER_DEVICE_RESET:
                LOG("app: the GL context was lost");
                globeview_gl_reset();
                break;
            case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
                LOG("app: window %dx%d px", e.window.data1, e.window.data2);
                break;
            default:
                if (gx && (e.type == SDL_EVENT_FINGER_DOWN || e.type == SDL_EVENT_FINGER_MOTION ||
                           e.type == SDL_EVENT_FINGER_UP || e.type == SDL_EVENT_FINGER_CANCELED))
                    e.tfinger.x = (e.tfinger.x * w - gx) / gw;
                globeview_event(&e, gw, h);
                break;
            }
        }
        {
            int arg;
            for (i = next_command(&arg); i; i = next_command(&arg))
                command(i, arg);
        }
        player_update();
        player_get_status(&st);
        if (st.state == PLAYER_PLAYING)
            played = 1;
        if (st.state != last_state) {
            LOG("state: %s -> %s", state_name(last_state), state_name(st.state));
            last_state = st.state;
        }
        report_playback(&st);
        now = SDL_GetTicks();
        if (now >= next_status_ms) {
            if (seconds || st.state != PLAYER_IDLE)
                log_status(&st, (int)((now - start_ms) / 1000));
            next_status_ms += seconds ? 1000 : 5000;
        }
        if (seconds && (now - start_ms >= (Uint64)seconds * 1000 || st.state == PLAYER_ERROR))
            running = 0;

        if (in_background) {
            /* No GL context: don't draw. Scripts wait too. */
            continue;
        }
        if (gx) {
            glViewport(0, 0, w, h);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        glViewport(gx, 0, gw, h);
        globeview_frame(gw, h, SDL_GetWindowDisplayScale(win), fixed_time);
        frame++;
        if (trace_frames > 0) {
            double la, lo, al;
            trace_frames--;
            globeview_camera(&la, &lo, &al);
            LOG("trace %d: %.4f %.4f %.1f km", frame, la, lo, al);
        }
        if (script && !script_step(win, &script, &script_wait))
            running = 0;
        if (shot && frame >= frames) {
            save_screenshot(win, shot);
            running = 0;
        }
        SDL_GL_SwapWindow(win); /* waits for vsync */
    }

    if (seconds) {
        if (st.song[0] || st.artist[0] || st.icy_url[0])
            LOG("track: artist \"%s\", song \"%s\"%s%s", st.artist, st.song,
                st.icy_url[0] ? ", station site " : "", st.icy_url);
        LOG("result: %s%s%s", played ? "played" : "did not play", st.error[0] ? ", error: " : "",
            st.error);
        rc = played ? 0 : 1;
    }
    save_config();
    player_shutdown();
    {
        player_status idle;
        SDL_zero(idle);
        cur_station = -1;
        test_url = NULL;
        report_playback(&idle);
    }
    curl_global_cleanup();
    globeview_shutdown();
    SDL_SetAtomicInt(&lib_ready, 0);
    library_free(&lib);
    userlist_free(&favs);
    userlist_free(&recent);
    config_free(&cfg);
    LOG("exit %d", rc);
    log_close();
    SDL_free(dir);
    SDL_GL_DestroyContext(gl);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return rc;
}
