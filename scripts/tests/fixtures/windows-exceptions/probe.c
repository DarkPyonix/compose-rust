/*
 * The C half of the probe check-windows-mingw-link.sh builds: an MSVC program that calls into
 * a Kotlin/Native static library the way the Host calls into the renderer.
 *
 *   probe catch      throws 1000 Kotlin exceptions three calls deep and catches each one;
 *                    exits 0 when every one was caught and the runtime's startup values are
 *                    what Kotlin set them to
 *   probe uncaught   lets one escape; Kotlin reports it and ends the process, so reaching the
 *                    line after the call is the failure
 */
#include <stdio.h>
#include <string.h>

int probe_catch(int times);
int probe_uncaught(void);

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "catch") == 0) {
        int caught = probe_catch(1000);
        printf("probe: caught %d of 1000\n", caught);
        fflush(stdout);
        return caught == 1000 ? 0 : 1;
    }
    if (argc == 2 && strcmp(argv[1], "uncaught") == 0) {
        probe_uncaught();
        printf("probe: an uncaught Kotlin exception came back to C\n");
        fflush(stdout);
        return 42;
    }
    fprintf(stderr, "usage: probe catch|uncaught\n");
    return 2;
}
