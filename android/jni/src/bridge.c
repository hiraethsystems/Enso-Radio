/* Calls into MainActivity. See bridge.h. */
#include "bridge.h"

#include <jni.h>

#include <SDL3/SDL.h>

#include "log.h"

/* Calls a void method on the activity, with up to two string arguments. */
static void call_activity(const char *method, const char *sig, const char *a, const char *b)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject act;
    jclass cls;
    jmethodID mid;
    jstring ja = NULL, jb = NULL;
    if (!env)
        return;
    act = (jobject)SDL_GetAndroidActivity();
    if (!act)
        return;
    cls = (*env)->GetObjectClass(env, act);
    mid = (*env)->GetMethodID(env, cls, method, sig);
    if (!mid) {
        (*env)->ExceptionClear(env);
        LOG("bridge: no method %s%s", method, sig);
    } else {
        if (a)
            ja = (*env)->NewStringUTF(env, a);
        if (b)
            jb = (*env)->NewStringUTF(env, b);
        if (a && b)
            (*env)->CallVoidMethod(env, act, mid, ja, jb);
        else
            (*env)->CallVoidMethod(env, act, mid);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOG("bridge: %s threw", method);
        }
        if (ja)
            (*env)->DeleteLocalRef(env, ja);
        if (jb)
            (*env)->DeleteLocalRef(env, jb);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, act);
}

void bridge_globe_label(const char *place, const char *detail)
{
    call_activity("setGlobeLabel", "(Ljava/lang/String;Ljava/lang/String;)V", place, detail);
}

void bridge_haptic_tick(void)
{
    call_activity("hapticTick", "()V", NULL, NULL);
}

void bridge_playback(int state, const char *station, const char *detail, const char *place)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject act;
    jclass cls;
    jmethodID mid;
    if (!env)
        return;
    act = (jobject)SDL_GetAndroidActivity();
    if (!act)
        return;
    cls = (*env)->GetObjectClass(env, act);
    mid = (*env)->GetMethodID(env, cls, "playbackUpdate",
                              "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!mid) {
        (*env)->ExceptionClear(env);
        LOG("bridge: no method playbackUpdate");
    } else {
        jstring a = (*env)->NewStringUTF(env, station);
        jstring b = (*env)->NewStringUTF(env, detail);
        jstring c = (*env)->NewStringUTF(env, place);
        (*env)->CallVoidMethod(env, act, mid, (jint)state, a, b, c);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOG("bridge: playbackUpdate threw");
        }
        (*env)->DeleteLocalRef(env, a);
        (*env)->DeleteLocalRef(env, b);
        (*env)->DeleteLocalRef(env, c);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, act);
}
