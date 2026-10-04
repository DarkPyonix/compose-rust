// What a live resize shows, counted.
//
// A drag of the window's edge is a run of sizes the view takes. For the picture to follow
// the edge, a frame has to be presented at each of those sizes before the next one
// arrives; a size that was left without one is shown as the previous frame scaled to fit,
// which is the stretch a reader sees. This keeps the count of both, with no AppKit in it,
// so a test can feed it the sequence of sizes and presents a drag produces and ask the
// same question the window asks of itself.
#ifndef DXC_APPKIT_RESIZE_H
#define DXC_APPKIT_RESIZE_H

#include <stdint.h>
#include <string.h>

struct dxc_resize_stats {
    int64_t steps;      // sizes the view took
    int64_t presented;  // frames handed to the screen
    int64_t stale;      // presented frames whose texture was not the view's size
    int64_t stretched;  // sizes that were left without a frame of their own
    int64_t callback_ticks;
    int64_t callback_max_ticks;
    int32_t view_width;
    int32_t view_height;
    int32_t step_unanswered;
};

static inline void dxc_resize_reset(struct dxc_resize_stats *stats) {
    memset(stats, 0, sizeof *stats);
}

/** The view took a new size, in pixels. */
static inline void dxc_resize_step(struct dxc_resize_stats *stats, int32_t width, int32_t height) {
    if (stats->step_unanswered) {
        stats->stretched++;
    }
    stats->steps++;
    stats->step_unanswered = 1;
    stats->view_width = width;
    stats->view_height = height;
}

/** A frame whose texture is this size was handed to the screen. */
static inline void dxc_resize_present(struct dxc_resize_stats *stats, int32_t width,
                                      int32_t height) {
    stats->presented++;
    if (width != stats->view_width || height != stats->view_height) {
        stats->stale++;
    } else {
        stats->step_unanswered = 0;
    }
}

/** Sizes left without a frame, counting the last one, which has no successor to say so. */
static inline int64_t dxc_resize_stretched(const struct dxc_resize_stats *stats) {
    return stats->stretched + (stats->step_unanswered ? 1 : 0);
}

#endif
