/*
 * Undefined references, from the library an application links, to every Host function the
 * renderer calls back into. Linux only.
 *
 * The renderer image resolves compose_rust_host_* from the executable at load time, so
 * the executable has to carry them in its dynamic symbol table. An executable's table
 * holds only what the link put there. The Host crate cannot ask for it: a build script's
 * link arguments (`-Wl,--export-dynamic`) reach that package's own binaries and stop, so
 * an application that merely depends on the crate linked without them and died with
 * "undefined symbol: compose_rust_host_release_batch" as soon as the renderer started.
 *
 * What does reach every application is the library it links, libcompose_rust_renderer.so.
 * When an executable defines a symbol that a shared library on its link line leaves
 * undefined, the linker exports that symbol from the executable, because the library will
 * need it at run time. This file makes the library leave the five undefined. The
 * references sit in a table nobody reads; holding the addresses is what makes the linker
 * record them as undefined symbols of the library, and `used` keeps the compiler from
 * dropping the table. Hidden, so the table itself adds nothing to the library's exports.
 *
 * The image needs these names too, but the image is not on the application's link line,
 * only behind this library's DT_NEEDED, and a linker does not export a symbol for a
 * dependency of a dependency.
 *
 * The prototypes are deliberately not the real ones. Only the addresses are taken, and
 * nothing here calls through them, so a signature that later changes cannot make this
 * file lie about it. The list itself is compared against the Host's definitions and the
 * renderer's declarations by scripts/tests/linux-host-references.test.sh, and the build
 * checks that the linked library really leaves every name the image needs undefined.
 */
#ifdef __linux__

extern void compose_rust_host_init(void);
extern void compose_rust_host_dispatch_event(void);
extern void compose_rust_host_render_frame(void);
extern void compose_rust_host_release_batch(void);
extern void compose_rust_host_shutdown(void);

__attribute__((used, visibility("hidden")))
void (*const compose_rust_host_references[])(void) = {
    compose_rust_host_init,
    compose_rust_host_dispatch_event,
    compose_rust_host_render_frame,
    compose_rust_host_release_batch,
    compose_rust_host_shutdown,
};

#endif
