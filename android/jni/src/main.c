/*
 * DX Garden for Android, native side.
 *
 * A2: the globe (OpenGL ES 3) with the bundled station list. Hold the
 * crosshair over a place and it plays. The log goes to
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
 *                     globe's GL objects as after a lost context), quit
 *   --verbose         (accepted; the log always goes to logcat)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <GLES3/gl3.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <curl/curl.h>

#include "bridge.h"
#include "config.h"
#include "earth.h"
#include "globe_view.h"
#include "library.h"
#include "load_report.h"
#include "log.h"
#include "player.h"
#include "station_source.h"

#define RING_BYTES (2 * 1024 * 1024) /* about 2 minutes at 128 kbps */
#define PREBUFFER_BYTES (64 * 1024)  /* about 4 s at 128 kbps */

static char *dir;           /* the data folder, ending in '/' */
static library lib;
static config cfg;
static int cur_station = -1;
static double fixed_time;   /* --time, or 0 for now */

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

/* ---- playing ---- */

static void play_station(int si)
{
    if (si < 0 || si >= lib.stations.count)
        return;
    cur_station = si;
    LOG("play: %s <%s>", lib.stations.items[si].name, lib.stations.items[si].url);
    player_start(lib.stations.items[si].url, PREBUFFER_BYTES);
}

static int playing_station(void)
{
    return cur_station;
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
        save_config();
        /* The GL context is still current here (SDL lets it go after). */
        earth_trim();
        break;
    case SDL_EVENT_DID_ENTER_FOREGROUND:
        LOG("app: back in the foreground");
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
    SDL_SetAppMetadata("DX Garden", DXG_VERSION, "systems.hiraeth.dxgarden");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_EVENTS)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
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
    first_run_copy();
    load_library();
    hooks.play = play_station;
    hooks.playing = playing_station;
    hooks.label = bridge_globe_label;
    hooks.tick = bridge_haptic_tick;
    globeview_init(&lib, &cfg, &hooks, "earth-day.pack", "earth-night.pack");
    SDL_AddEventWatch(lifecycle, NULL);
    if (go) {
        double a, b, c;
        if (sscanf(go, "%lf %lf %lf", &a, &b, &c) == 3)
            globeview_goto(a, b, c);
    }

    if (url) {
        LOG("play: %s%s", url, seconds ? " (test run)" : "");
        player_start(url, PREBUFFER_BYTES);
    }
    start_ms = SDL_GetTicks();
    next_status_ms = start_ms + (seconds ? 1000 : 5000);

    while (running) {
        SDL_Event e;
        Uint64 now;
        int w, h;
        SDL_GetWindowSizeInPixels(win, &w, &h);
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
                globeview_event(&e, w, h);
                break;
            }
        }
        player_update();
        player_get_status(&st);
        if (st.state == PLAYER_PLAYING)
            played = 1;
        if (st.state != last_state) {
            LOG("state: %s -> %s", state_name(last_state), state_name(st.state));
            last_state = st.state;
        }
        now = SDL_GetTicks();
        if (now >= next_status_ms) {
            if (seconds || st.state != PLAYER_IDLE)
                log_status(&st, (int)((now - start_ms) / 1000));
            next_status_ms += seconds ? 1000 : 5000;
        }
        if (seconds && (now - start_ms >= (Uint64)seconds * 1000 || st.state == PLAYER_ERROR))
            running = 0;

        glViewport(0, 0, w, h);
        globeview_frame(w, h, SDL_GetWindowDisplayScale(win), fixed_time);
        frame++;
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
    curl_global_cleanup();
    globeview_shutdown();
    library_free(&lib);
    config_free(&cfg);
    LOG("exit %d", rc);
    log_close();
    SDL_free(dir);
    SDL_GL_DestroyContext(gl);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return rc;
}
