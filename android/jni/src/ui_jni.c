/*
 * The interface's view of the library: JNI functions behind Native.kt.
 * Called on Android's threads (the main thread or a background one for
 * search). The library is read-only once loaded (app.h); before then
 * everything answers empty.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <jni.h>

#include "app.h"
#include "bridge.h"
#include "player.h"
#include "search.h"

#define JNI_FN(name) Java_systems_hiraeth_dxgarden_Native_##name

static jobjectArray strings(JNIEnv *env, const char **v, int n)
{
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray a = (*env)->NewObjectArray(env, n, cls, NULL);
    int i;
    for (i = 0; a && i < n; i++) {
        jstring s = bridge_jstring(env, v[i] ? v[i] : "");
        (*env)->SetObjectArrayElement(env, a, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    (*env)->DeleteLocalRef(env, cls);
    return a;
}

static jintArray ints(JNIEnv *env, const int *v, int n)
{
    jintArray a = (*env)->NewIntArray(env, n);
    if (a && n > 0)
        (*env)->SetIntArrayRegion(env, a, 0, n, (const jint *)v);
    return a;
}

JNIEXPORT jint JNICALL JNI_FN(stationCount)(JNIEnv *env, jclass cls)
{
    const library *lib = app_library();
    (void)env;
    (void)cls;
    return lib ? lib->stations.count : 0;
}

/* name, country, station count, "1" if Country-wide */
JNIEXPORT jobjectArray JNICALL JNI_FN(place)(JNIEnv *env, jclass cls, jint pl)
{
    const library *lib = app_library();
    const char *v[4] = { "", "", "0", "0" };
    char count[16];
    (void)cls;
    if (lib && pl >= 0 && pl < lib->n_places) {
        const place *p = &lib->places[pl];
        snprintf(count, sizeof(count), "%d", p->n_stations);
        v[0] = p->name;
        v[1] = p->country;
        v[2] = count;
        v[3] = p->country_wide ? "1" : "0";
    }
    return strings(env, v, 4);
}

JNIEXPORT jintArray JNICALL JNI_FN(placeStations)(JNIEnv *env, jclass cls, jint pl)
{
    const library *lib = app_library();
    (void)cls;
    if (!lib || pl < 0 || pl >= lib->n_places)
        return ints(env, NULL, 0);
    return ints(env, lib->places[pl].stations, lib->places[pl].n_stations);
}

/* name, genre, city, country, url, place index, notes */
JNIEXPORT jobjectArray JNICALL JNI_FN(station)(JNIEnv *env, jclass cls, jint si)
{
    const library *lib = app_library();
    const char *v[7] = { "", "", "", "", "", "-1", "" };
    char pl[16];
    (void)cls;
    if (lib && si >= 0 && si < lib->stations.count) {
        const station *s = &lib->stations.items[si];
        snprintf(pl, sizeof(pl), "%d", s->place);
        v[0] = s->name;
        v[1] = s->genre;
        v[2] = s->city;
        v[3] = s->country;
        v[4] = s->url;
        v[5] = pl;
        v[6] = s->notes;
    }
    return strings(env, v, 7);
}

/* [number of places, places..., stations...] */
JNIEXPORT jintArray JNICALL JNI_FN(search)(JNIEnv *env, jclass cls, jstring jq, jint max)
{
    const library *lib = app_library();
    const char *q;
    search_result r;
    int *out, n;
    jintArray a;
    (void)cls;
    if (!lib || !jq)
        return ints(env, NULL, 0);
    q = (*env)->GetStringUTFChars(env, jq, NULL);
    memset(&r, 0, sizeof(r));
    if (!q || library_search(lib, q, max, &r) != 0) {
        if (q)
            (*env)->ReleaseStringUTFChars(env, jq, q);
        return ints(env, NULL, 0);
    }
    (*env)->ReleaseStringUTFChars(env, jq, q);
    n = 1 + r.n_places + r.n_stations;
    out = malloc((size_t)n * sizeof(int));
    if (!out) {
        search_result_free(&r);
        return ints(env, NULL, 0);
    }
    out[0] = r.n_places;
    if (r.n_places)
        memcpy(out + 1, r.places, (size_t)r.n_places * sizeof(int));
    if (r.n_stations)
        memcpy(out + 1 + r.n_places, r.stations, (size_t)r.n_stations * sizeof(int));
    a = ints(env, out, n);
    free(out);
    search_result_free(&r);
    return a;
}

/* which: 0 countries, 1 genres */
static const group *groups(const library *lib, int which, int *n)
{
    *n = which ? lib->n_genres : lib->n_countries;
    return which ? lib->genres : lib->countries;
}

JNIEXPORT jobjectArray JNICALL JNI_FN(groupNames)(JNIEnv *env, jclass cls, jint which)
{
    const library *lib = app_library();
    const group *g;
    const char **v;
    int n = 0, i;
    jobjectArray a;
    (void)cls;
    if (!lib)
        return strings(env, NULL, 0);
    g = groups(lib, which, &n);
    v = malloc((size_t)(n ? n : 1) * sizeof(*v));
    if (!v)
        return strings(env, NULL, 0);
    for (i = 0; i < n; i++)
        v[i] = g[i].name;
    a = strings(env, v, n);
    free(v);
    return a;
}

/* Stations in each group. */
JNIEXPORT jintArray JNICALL JNI_FN(groupCounts)(JNIEnv *env, jclass cls, jint which)
{
    const library *lib = app_library();
    const group *g;
    int n = 0, i, *v;
    jintArray a;
    (void)cls;
    if (!lib)
        return ints(env, NULL, 0);
    g = groups(lib, which, &n);
    v = malloc((size_t)(n ? n : 1) * sizeof(int));
    if (!v)
        return ints(env, NULL, 0);
    for (i = 0; i < n; i++)
        v[i] = which ? g[i].n : g[i].n_stations;
    a = ints(env, v, n);
    free(v);
    return a;
}

/* A country's places, or a genre's stations. */
JNIEXPORT jintArray JNICALL JNI_FN(groupItems)(JNIEnv *env, jclass cls, jint which, jint gi)
{
    const library *lib = app_library();
    const group *g;
    int n = 0;
    (void)cls;
    if (!lib)
        return ints(env, NULL, 0);
    g = groups(lib, which, &n);
    if (gi < 0 || gi >= n)
        return ints(env, NULL, 0);
    return ints(env, g[gi].items, g[gi].n);
}

JNIEXPORT jintArray JNICALL JNI_FN(list)(JNIEnv *env, jclass cls, jint which)
{
    int n = app_list_count(which), *v;
    jintArray a;
    (void)cls;
    v = malloc((size_t)(n ? n : 1) * sizeof(int));
    if (!v)
        return ints(env, NULL, 0);
    n = app_list(which, v, n);
    a = ints(env, v, n);
    free(v);
    return a;
}

JNIEXPORT jboolean JNICALL JNI_FN(isFavourite)(JNIEnv *env, jclass cls, jint si)
{
    (void)env;
    (void)cls;
    return app_is_favourite(si) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(toggleFavourite)(JNIEnv *env, jclass cls, jint si)
{
    (void)env;
    (void)cls;
    return app_toggle_favourite(si) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(command)(JNIEnv *env, jclass cls, jint cmd, jint arg)
{
    (void)env;
    (void)cls;
    app_command(cmd, arg);
}

JNIEXPORT void JNICALL JNI_FN(setGlobeLeft)(JNIEnv *env, jclass cls, jint px)
{
    (void)env;
    (void)cls;
    app_set_globe_left(px);
}

/* For Now Playing: codec, rate (Hz), channels, kbps, stream's name,
 * website, title, buffer fill %, error */
JNIEXPORT jobjectArray JNICALL JNI_FN(status)(JNIEnv *env, jclass cls)
{
    player_status st;
    char rate[16], ch[8], kbps[16], fill[8];
    const char *v[9];
    (void)cls;
    player_get_status(&st);
    snprintf(rate, sizeof(rate), "%ld", st.rate);
    snprintf(ch, sizeof(ch), "%d", st.channels);
    snprintf(kbps, sizeof(kbps), "%d", st.bitrate_kbps);
    snprintf(fill, sizeof(fill), "%u", st.fill_pct);
    v[0] = st.codec;
    v[1] = rate;
    v[2] = ch;
    v[3] = kbps;
    v[4] = st.icy_name;
    v[5] = st.icy_url;
    v[6] = st.title;
    v[7] = fill;
    v[8] = st.error;
    return strings(env, v, 9);
}
