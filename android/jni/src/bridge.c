/* Calls into MainActivity. See bridge.h. */
#include "bridge.h"

#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

#include <SDL3/SDL.h>

#include "log.h"

jstring bridge_jstring(JNIEnv *env, const char *s)
{
    size_t n = s ? strlen(s) : 0, i = 0, o = 0;
    jchar small[256], *buf = n < 256 ? small : malloc(n * sizeof(jchar));
    jstring js;
    if (!buf)
        return (*env)->NewStringUTF(env, "");
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        Uint32 cp = 0xFFFD;
        int len = 1, k;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            cp = c & 0x07;
        } else {
            len = 0;
        }
        if (len == 0 || i + (size_t)len > n) {
            cp = 0xFFFD;
            len = 1;
        } else {
            for (k = 1; k < len; k++) {
                unsigned char d = (unsigned char)s[i + k];
                if ((d & 0xC0) != 0x80)
                    break;
                cp = (cp << 6) | (d & 0x3F);
            }
            if (k < len || (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) ||
                (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)) || (cp >= 0xD800 && cp < 0xE000)) {
                cp = 0xFFFD;
                len = 1;
            }
        }
        i += (size_t)len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            buf[o++] = (jchar)(0xD800 + (cp >> 10));
            buf[o++] = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            buf[o++] = (jchar)cp;
        }
    }
    js = (*env)->NewString(env, buf, (jsize)o);
    if (buf != small)
        free(buf);
    return js;
}

/* Calls a void method on the activity. */
static void call_activity(const char *method, const char *sig, ...)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject act;
    jclass cls;
    jmethodID mid;
    va_list ap;
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
        va_start(ap, sig);
        (*env)->CallVoidMethodV(env, act, mid, ap);
        va_end(ap);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOG("bridge: %s threw", method);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, act);
}

void bridge_globe_label(int place, const char *name, const char *detail)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jstring a, b;
    if (!env)
        return;
    a = bridge_jstring(env, name);
    b = bridge_jstring(env, detail);
    call_activity("setGlobeLabel", "(ILjava/lang/String;Ljava/lang/String;)V", (jint)place, a, b);
    (*env)->DeleteLocalRef(env, a);
    (*env)->DeleteLocalRef(env, b);
}

void bridge_haptic_tick(void)
{
    call_activity("hapticTick", "()V");
}

void bridge_library_ready(int stations)
{
    call_activity("libraryReady", "(I)V", (jint)stations);
}

void bridge_playback(int state, int station, const char *name, const char *detail,
                     const char *place)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jstring a, b, c;
    if (!env)
        return;
    a = bridge_jstring(env, name);
    b = bridge_jstring(env, detail);
    c = bridge_jstring(env, place);
    call_activity("playbackUpdate", "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                  (jint)state, (jint)station, a, b, c);
    (*env)->DeleteLocalRef(env, a);
    (*env)->DeleteLocalRef(env, b);
    (*env)->DeleteLocalRef(env, c);
}
