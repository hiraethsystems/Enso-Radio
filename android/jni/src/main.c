/*
 * DX Garden for Android, native side.
 *
 * A1: a plain screen that plays one built-in stream through the player
 * and decoders from the Linux app. The screen's colour shows the player's
 * state (blue connecting or buffering, green playing, amber reconnecting,
 * red error). The log goes to log-<version>-b<build>.txt in the app's
 * external files folder and to logcat (tag DXGarden).
 *
 * Test options, from intent extras in debuggable builds (MainActivity):
 *   --play URL        play this instead of the built-in stream
 *   --seconds N       stop after N seconds, logging the status each second,
 *                     and close the app ("result: played" in the log)
 *   --verbose         (accepted; the log always goes to logcat)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <curl/curl.h>

#include "log.h"
#include "player.h"

#define RING_BYTES (2 * 1024 * 1024) /* about 2 minutes at 128 kbps */
#define PREBUFFER_BYTES (64 * 1024)  /* about 4 s at 128 kbps */

/* A1's built-in stream: SomaFM Groove Salad, MP3 128 kbps over HTTPS. */
#define DEFAULT_URL "https://ice1.somafm.com/groovesalad-128-mp3"

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

static void open_log(const char *dir)
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

static void state_colour(player_state s, Uint8 *r, Uint8 *g, Uint8 *b)
{
    switch (s) {
    case PLAYER_PLAYING: *r = 0x0e; *g = 0x5c; *b = 0x3a; break;      /* green */
    case PLAYER_RECONNECTING: *r = 0x8a; *g = 0x5a; *b = 0x00; break; /* amber */
    case PLAYER_ERROR: *r = 0x8c; *g = 0x1c; *b = 0x1c; break;        /* red */
    default: *r = 0x0b; *g = 0x1e; *b = 0x3c; break;                  /* night blue */
    }
}

int main(int argc, char *argv[])
{
    const char *url = DEFAULT_URL;
    int seconds = 0, i, played = 0, running = 1, rc = 0;
    SDL_Window *win;
    SDL_Renderer *ren;
    char *dir;
    player_status st;
    player_state last_state = PLAYER_IDLE;
    Uint64 start_ms, next_status_ms;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--play") == 0 && i + 1 < argc)
            url = argv[++i];
        else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc)
            seconds = atoi(argv[++i]);
    }

    SDL_SetAppMetadata("DX Garden", DXG_VERSION, "systems.hiraeth.dxgarden");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_EVENTS)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    dir = data_dir();
    open_log(dir);
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

    /* Not SDL_WINDOW_FULLSCREEN: that hides the system bars (immersive mode).
       The app is edge to edge instead, with the bars drawn over it. */
    win = SDL_CreateWindow("DX Garden", 0, 0, 0);
    ren = win ? SDL_CreateRenderer(win, NULL) : NULL;
    if (!ren) {
        LOG("window or renderer failed: %s", SDL_GetError());
        return 1;
    }

    LOG("play: %s%s", url, seconds ? " (test run)" : "");
    player_start(url, PREBUFFER_BYTES);
    start_ms = SDL_GetTicks();
    next_status_ms = start_ms + (seconds ? 1000 : 5000);

    while (running) {
        SDL_Event e;
        Uint8 r, g, b;
        Uint64 now;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_EVENT_QUIT || e.type == SDL_EVENT_TERMINATING)
                running = 0;
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
            log_status(&st, (int)((now - start_ms) / 1000));
            next_status_ms += seconds ? 1000 : 5000;
        }
        if (seconds && (now - start_ms >= (Uint64)seconds * 1000 || st.state == PLAYER_ERROR))
            running = 0;

        state_colour(st.state, &r, &g, &b);
        SDL_SetRenderDrawColor(ren, r, g, b, 0xff);
        SDL_RenderClear(ren);
        SDL_RenderPresent(ren); /* waits for vsync */
    }

    if (seconds) {
        if (st.song[0] || st.artist[0] || st.icy_url[0])
            LOG("track: artist \"%s\", song \"%s\"%s%s", st.artist, st.song,
                st.icy_url[0] ? ", station site " : "", st.icy_url);
        LOG("result: %s%s%s", played ? "played" : "did not play", st.error[0] ? ", error: " : "",
            st.error);
        rc = played ? 0 : 1;
    }
    player_shutdown();
    curl_global_cleanup();
    LOG("exit %d", rc);
    log_close();
    SDL_free(dir);
    SDL_DestroyRenderer(ren);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return rc;
}
