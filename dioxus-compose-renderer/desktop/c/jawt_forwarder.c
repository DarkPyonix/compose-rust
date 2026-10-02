/*
 * Built as lib/libjawt.dylib.
 *
 * Skiko opens <java.home>/lib/libjawt.dylib by path to find JAWT_GetAWT. JAWT is linked
 * statically into the renderer library, so this file exists only to forward that call.
 */
#include <dlfcn.h>
#include <stddef.h>

typedef unsigned char (*get_awt_fn)(void *env, void *awt);

unsigned char JAWT_GetAWT(void *env, void *awt) {
    static get_awt_fn forward;
    if (forward == NULL) {
        forward = (get_awt_fn)dlsym(RTLD_DEFAULT, "dioxus_compose_jawt_get_awt");
    }
    return forward != NULL ? forward(env, awt) : 0;
}
