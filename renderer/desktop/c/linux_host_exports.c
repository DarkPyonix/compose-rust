/*
 * The one thing libcompose_rust_host_exports.so defines. Linux, Kotlin/Native renderer only.
 *
 * That renderer is a static archive linked into the application, and it finds the Host's
 * compose_rust_host_* functions with dlsym, which reads the executable's dynamic symbol
 * table. An executable's table holds only what its link put there, and the Host crate
 * cannot ask on an application's behalf: a build script's link arguments (-rdynamic) reach
 * that package's own binaries and stop.
 *
 * What does reach an application's link is a library the crate names. This small shared
 * library leaves every Host function undefined (linux_host_references.c is linked into it
 * as well), and a linker exports from an executable whatever a shared library it links
 * needs. GNU ld only counts a library it keeps, though: Rust links with --as-needed, so a
 * library nothing in the executable refers to is dropped, and what it needs is forgotten
 * with it. This byte is what the Host refers to so that the library is kept. Its value
 * means nothing.
 *
 * Nothing a linker reads at link time can do this on its own. A version script, an EXTERN
 * in a linker script, and the same library without this byte were each tried against GNU
 * ld and lld, from a crate that depends on another the way an application depends on this
 * one; only a shared library the executable really needs exported anything under GNU ld.
 */
#ifdef __linux__

__attribute__((visibility("default")))
const unsigned char compose_rust_renderer_host_exports = 1;

#endif
