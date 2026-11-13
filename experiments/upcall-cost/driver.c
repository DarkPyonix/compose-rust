// E0 driver: builds an isolate from libprobe.dylib and times upcalls and handshakes.
#include <mach/mach_time.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "graal_isolate.h"
#include "libprobe.h"

void hs_call(void);
void hs_stop(void);
void hs_reset(void);
void hs_baseline_start(pthread_t *t);
int32_t probe_nop(int32_t);

static uint64_t now_ns(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return mach_absolute_time() * tb.numer / tb.denom;
}
static int cmp(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}
static void report(const char *name, double *v, int n, const char *unit) {
    qsort(v, n, sizeof *v, cmp);
    printf("%-46s median %9.1f  p99 %9.1f  max %10.1f  %s\n", name, v[n / 2], v[(int)(n * 0.99)], v[n - 1], unit);
}

#define BATCHES 2000
#define BATCH 1000

typedef void (*thunk)(void *);
static graal_isolate_t *isolate;
static graal_isolatethread_t *thr;

static void do_empty(void *c) { (void)c; probe_empty(thr); }
static void do_struct(void *c) { probe_struct(thr, c); }
static void do_current(void *c) { (void)c; probe_empty(graal_get_current_thread(isolate)); }
static void do_c_nop(void *c) { (void)c; volatile int32_t s = probe_nop(1); (void)s; }

// Per-call cost from batch means (the clock ticks at 41.7 ns, too coarse for one call).
static void batched(const char *name, thunk f, void *ctx) {
    static double per[BATCHES];
    for (int b = 0; b < BATCHES; b++) for (int i = 0; i < BATCH; i++) f(ctx);
    for (int b = 0; b < BATCHES; b++) {
        uint64_t t0 = now_ns();
        for (int i = 0; i < BATCH; i++) f(ctx);
        per[b] = (now_ns() - t0) / (double)BATCH;
    }
    report(name, per, BATCHES, "ns/call (batch means)");
}

static void *attach_cycle(void *arg) {
    double *out = arg;
    graal_isolatethread_t *t;
    uint64_t t0 = now_ns();
    graal_attach_thread(isolate, &t);
    probe_empty(t);
    graal_detach_thread(t);
    *out = (double)(now_ns() - t0);
    return 0;
}

int main(void) {
    if (graal_create_isolate(NULL, &isolate, &thr) != 0) { fprintf(stderr, "isolate\n"); return 1; }
    struct { int32_t kind; float x, y; int32_t buttons, modifiers, key, cp; char text[16]; } ev = {1, 10, 20, 0, 0, 65, 65, "a"};

    printf("== (a) upcall C -> Java\n");
    batched("a1 empty, IsolateThread passed", do_empty, 0);
    batched("a2 struct(32B), IsolateThread passed", do_struct, &ev);
    batched("a3 empty, graal_get_current_thread each call", do_current, 0);

    // Singly timed (quantised to 41.7 ns): shows the tail the batches average away.
    { enum { N = 200000 }; static double v[N];
      for (int i = 0; i < 20000; i++) probe_empty(thr);
      for (int i = 0; i < N; i++) { uint64_t t0 = now_ns(); probe_empty(thr); v[i] = (double)(now_ns() - t0); }
      report("a1 empty, singly timed (41.7 ns ticks)", v, N, "ns/call"); }

    { enum { N = 20000 }; static double v[N];
      for (int i = 0; i < N; i++) { pthread_t t; pthread_create(&t, 0, attach_cycle, &v[i]); pthread_join(t, 0); }
      report("a4 fresh thread: attach + empty + detach", v, N, "ns (includes pthread-local setup)"); }

    printf("== (c) downcall Java -> C (timed in Java)\n");
    { double out[4] = {0}; probe_bench_downcall(thr, out, BATCHES, BATCH);
      printf("%-46s median %9.1f  p99 %9.1f  max %10.1f  ns/call (batch means)\n", "c1 @CFunction probe_nop", out[0], out[1], out[2]); }
    batched("c0 plain C call (loop overhead reference)", do_c_nop, 0);

    printf("== (b) handshake: signal a parked thread, wait for its reply\n");
    { enum { N = 100000 }; static double v[N];
      pthread_t base; hs_reset(); hs_baseline_start(&base);
      for (int i = 0; i < 5000; i++) hs_call();
      for (int i = 0; i < N; i++) { uint64_t t0 = now_ns(); hs_call(); v[i] = (double)(now_ns() - t0); }
      report("b0 baseline: parked plain C thread", v, N, "ns/round trip");
      hs_stop(); pthread_join(base, 0);
      hs_reset();
      probe_start_server(thr);
      for (int i = 0; i < 5000; i++) hs_call();
      for (int i = 0; i < N; i++) { uint64_t t0 = now_ns(); hs_call(); v[i] = (double)(now_ns() - t0); }
      report("b1 Java thread parked in @CFunction", v, N, "ns/round trip"); }
    hs_stop();
    return 0;
}
