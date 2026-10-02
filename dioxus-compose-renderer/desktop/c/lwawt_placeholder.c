/*
 * Built as lib/libawt_lwawt.dylib.
 *
 * libawt's initialisation loads the macOS toolkit library by path from its own directory.
 * The toolkit is linked statically into the renderer library and its JNI functions resolve
 * from there, so the file only has to exist and load.
 */
void dioxus_compose_lwawt_placeholder(void) {}
