// Plain C side of the experiment. Lives in its own dylib, like the window code lives
// outside the native-image library in the real renderer: the image calls these through
// @CFunction and the driver calls them directly.
#include <pthread.h>
#include <stdint.h>

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv_req = PTHREAD_COND_INITIALIZER;
static pthread_cond_t cv_rep = PTHREAD_COND_INITIALIZER;
static uint64_t req_seq, rep_seq, served;
static int stop_flag;

// (c) the reference downcall: a function that does as little as a function can.
__attribute__((noinline)) int32_t probe_nop(int32_t x) { return x + 1; }

// (b) the Java side parks here until the main thread posts a request. Returns 0 on stop.
int32_t hs_wait_request(void) {
    pthread_mutex_lock(&mu);
    while (req_seq == served && !stop_flag) pthread_cond_wait(&cv_req, &mu);
    int32_t ok = !stop_flag;
    served = req_seq;
    pthread_mutex_unlock(&mu);
    return ok;
}

// The Java side answers.
void hs_reply(void) {
    pthread_mutex_lock(&mu);
    rep_seq = served;
    pthread_cond_signal(&cv_rep);
    pthread_mutex_unlock(&mu);
}

// The main thread: signal the parked thread and wait for its reply. One round trip.
void hs_call(void) {
    pthread_mutex_lock(&mu);
    uint64_t want = ++req_seq;
    pthread_cond_signal(&cv_req);
    while (rep_seq != want) pthread_cond_wait(&cv_rep, &mu);
    pthread_mutex_unlock(&mu);
}

void hs_stop(void) {
    pthread_mutex_lock(&mu);
    stop_flag = 1;
    pthread_cond_broadcast(&cv_req);
    pthread_mutex_unlock(&mu);
}

// Baseline for (b): the same handshake between two plain C threads, no Java at all.
static void *c_server(void *arg) {
    (void)arg;
    while (hs_wait_request()) hs_reply();
    return 0;
}
void hs_baseline_start(pthread_t *t) { pthread_create(t, 0, c_server, 0); }
void hs_reset(void) { pthread_mutex_lock(&mu); stop_flag = 0; pthread_mutex_unlock(&mu); }
