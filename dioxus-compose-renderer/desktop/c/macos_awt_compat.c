/*
 * Symbols AWT and Skiko look up at run time that the statically linked macOS AWT in
 * Liberica NIK does not provide. Linked into the renderer library.
 */
#include <stdint.h>

#define JNI_VERSION_1_8 0x00010008

/*
 * osxui is linked statically, and a statically linked JNI library must define
 * JNI_OnLoad_<name>. The archive has no initialisation of its own to run.
 */
int32_t JNI_OnLoad_osxui(void *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_8;
}

/* Implemented by the statically linked libjawt.a. */
unsigned char JAWT_GetAWT(void *env, void *awt);

/*
 * Reached by the libjawt.dylib forwarder. Exported under its own name so the forwarder's
 * lookup cannot resolve to the forwarder itself.
 */
unsigned char dioxus_compose_jawt_get_awt(void *env, void *awt) {
    return JAWT_GetAWT(env, awt);
}
