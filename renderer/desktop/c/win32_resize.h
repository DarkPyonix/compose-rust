#ifndef DXC_WIN32_RESIZE_H
#define DXC_WIN32_RESIZE_H

#include <stdint.h>

// What the window knows about its own size, and about being dragged by an edge.
//
// Kept apart from the window because none of it needs one. Every decision here is
// arithmetic on five numbers, and a machine with no Windows on it can still compile this
// header and check that the arithmetic says what it should. What the answers lead to, a
// swapchain refitted and a frame drawn, is the only part that needs the platform.

/**
 * The size the window has been given, the size it is drawn at, and whether a drag is on.
 *
 * The two sizes are apart because they disagree for as long as it takes to refit the
 * swapchain, and because a refit can be refused: the size a frame is drawn at is the one
 * the swapchain was actually made, never the one that was asked for.
 */
struct dxc_resize {
    // The size the client area was last reported to be, in pixels, waiting for a frame
    // to fit the swapchain to it. Zero once it has been taken.
    int32_t wanted_width;
    int32_t wanted_height;
    // The size the swapchain was last made, which is the size a frame is drawn at.
    int32_t fitted_width;
    int32_t fitted_height;
    // Between the reader taking hold of the window's edge and letting go of it. Windows
    // runs a loop of its own for the whole of that, inside the handler for the press
    // that began it, so the renderer's frame loop gets no turn from the first press to
    // the last release.
    int dragging;
};

/** Writes down a size the window has just been given. */
static void dxc_resize_note(struct dxc_resize *resize, int32_t width, int32_t height) {
    resize->wanted_width = width;
    resize->wanted_height = height;
}

/**
 * Takes the size the next frame has to be drawn at.
 *
 * Zero when there is nothing to do, which is the ordinary answer: a window that is not
 * being resized has been given no size, and a window that has just been shown reports the
 * size its swapchain was already made at. The note is cleared either way, because a size
 * that matches what is already fitted has been dealt with by being read.
 */
static int dxc_resize_take(struct dxc_resize *resize, int32_t *width, int32_t *height) {
    int32_t wanted_width = resize->wanted_width;
    int32_t wanted_height = resize->wanted_height;
    if (wanted_width <= 0 || wanted_height <= 0) {
        return 0;
    }
    resize->wanted_width = 0;
    resize->wanted_height = 0;
    if (wanted_width == resize->fitted_width && wanted_height == resize->fitted_height) {
        return 0;
    }
    *width = wanted_width;
    *height = wanted_height;
    return 1;
}

/**
 * Records the size the swapchain was made.
 *
 * Called where a refit succeeded and where the swapchain was first created, and nowhere
 * else. A refusal leaves this as it was, so the window carries on drawing at the size it
 * has rather than at the size it was denied.
 */
static void dxc_resize_fitted(struct dxc_resize *resize, int32_t width, int32_t height) {
    resize->fitted_width = width;
    resize->fitted_height = height;
}

/** The reader has taken hold of the window's edge. */
static void dxc_resize_begin_drag(struct dxc_resize *resize) {
    resize->dragging = 1;
}

/** The reader has let go of it. */
static void dxc_resize_end_drag(struct dxc_resize *resize) {
    resize->dragging = 0;
}

/**
 * Whether a size arriving now has to be drawn where it arrives.
 *
 * True inside a drag and false outside one. Outside, writing the size down is enough: the
 * frame loop is running and the next frame takes it. Inside, there is no next frame until
 * the reader lets go, so a size left for the frame loop is a size nothing draws, and what
 * the screen shows for the length of the drag is the last frame stretched to fit.
 */
static int dxc_resize_draw_here(const struct dxc_resize *resize) {
    return resize->dragging;
}

#endif
