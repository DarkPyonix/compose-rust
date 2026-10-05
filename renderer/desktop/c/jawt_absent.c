/*
 * Built as lib/libjawt.so on Linux.
 *
 * Skiko opens <java.home>/lib/libjawt.so by path when its AWT classes are initialised. The
 * Linux renderer has no Java toolkit, so there is no AWT canvas to hand out: JAWT_GetAWT
 * reports failure, which is the answer a caller must already handle. The file exists so that
 * the load by path succeeds without the toolkit's own libawt being staged beside it.
 */
unsigned char JAWT_GetAWT(void *env, void *awt) {
    (void)env;
    (void)awt;
    return 0;
}
