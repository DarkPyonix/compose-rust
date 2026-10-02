// An X11 window and GLX framebuffer owned by the renderer. XWayland accepts the same
// X11 connection on Wayland desktops. Skia paints; this file only presents and records.
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/cursorfont.h>
#include <X11/keysym.h>
#include <X11/extensions/sync.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>

enum {
    DXC_EVENT_POINTER_MOVE = 1,
    DXC_EVENT_POINTER_DOWN = 2,
    DXC_EVENT_POINTER_UP = 3,
    DXC_EVENT_SCROLL = 4,
    DXC_EVENT_KEY_DOWN = 5,
    DXC_EVENT_KEY_UP = 6,
    // XIM, ibus and fcitx text composition is separate work. These kinds are declared
    // for the shared reader, but this window does not populate text events.
    DXC_EVENT_TEXT_COMMIT = 7,
    DXC_EVENT_TEXT_COMPOSE = 8,
};

#define DXC_TEXT_BYTES 96
#define DXC_EVENT_CAPACITY 256

// How many things a screen may say it has. Enough for a screen and not for a document,
// which is the same number the renderer is willing to send.
#define DXC_ELEMENT_CAPACITY 256

// The shapes a pointer may take, in the order both sides agree on: arrow, hand, text,
// crosshair, and the two resize arrows.
#define DXC_CURSOR_SHAPES 6

struct dxc_event {
    int32_t kind;
    float x;
    float y;
    int32_t buttons;
    int32_t modifiers;
    int32_t key_code;
    int32_t code_point;
    char text[DXC_TEXT_BYTES];
};

// One thing in the window, as a reader who cannot see it would meet it. The same fields
// and the same order as the other two windows declare, because the renderer writes the
// records once and every platform reads that one layout.
struct dxc_element {
    int32_t role;
    // In points from the top left of the window, which is what the scene measures in.
    float x;
    float y;
    float width;
    float height;
    char label[DXC_TEXT_BYTES];
};

// Five pointer slots, matching the size and offsets of the other two windows. The
// members name X11 and GLX resources at the corresponding positions.
struct dxc_native_window {
    void *window;
    void *view;
    void *device;
    void *queue;
    void *layer;
};

static Display *dxc_display;
static Window dxc_window;
static GLXContext dxc_context;
static Colormap dxc_colormap;
static Atom dxc_delete_window;
static int dxc_width;
static int dxc_height;
static int dxc_closed;
static struct dxc_event dxc_events[DXC_EVENT_CAPACITY];
static int dxc_event_head;
static int dxc_event_count;

// Set while a turn of the event loop is running, so that a second turn started from inside
// it does nothing.
//
// A turn can draw a frame, and a frame that asked this file anything used to read events as
// a side effect. A nested read would take a resize still arriving out from under the turn
// already handling one, and the frame for it would be refused as re-entrant and then
// forgotten, which is the lateness this file exists to remove. So reading happens in one
// place, dxc_native_pump, and this is what keeps it that way whoever calls in next.
static int dxc_pumping;

// What the renderer registered for drawing a frame, and the thread it asked to be called
// back on.
//
// The window is this file's and the frame is the renderer's, so the only way to produce
// one from here is a pointer the other side handed over. Null until it does, which is
// every moment before the first frame and every moment after the window has closed.
static int32_t (*dxc_draw_frame)(void *isolate_thread);
static void *dxc_frame_thread;

// The window manager's frame synchronisation, where the manager offered one.
//
// It hands out a number with a resize and holds the frame it is about to show until the
// counter carries that number. That is what puts the edge it moved and the drawing inside
// it on the screen in one step: without it the frame changes when the manager says so and
// the drawing arrives when it is ready, and the gap between the two is a strip of the
// window that has been claimed and not painted.
static Atom dxc_protocols;
static Atom dxc_sync_request;
static XSyncCounter dxc_sync_counter;
static XSyncValue dxc_sync_value;
static int dxc_sync_owed;

// Made once each and kept, because a cursor is a server resource and the scene asks for
// one whenever the pointer crosses into something new.
static Cursor dxc_cursors[DXC_CURSOR_SHAPES];
static int32_t dxc_cursor_shape = -1;

// The newest tree the scene pushed. Kept rather than published: what answers a reader on
// this desktop is AT-SPI, which is its own work and is not here yet, and holding the tree
// is what lets that work read from a window that is already describing itself.
static struct dxc_element dxc_elements[DXC_ELEMENT_CAPACITY];
static int32_t dxc_element_count;

static void dxc_push_event(struct dxc_event event) {
    if (dxc_event_count == DXC_EVENT_CAPACITY) {
        dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
        dxc_event_count--;
    }
    dxc_events[(dxc_event_head + dxc_event_count) % DXC_EVENT_CAPACITY] = event;
    dxc_event_count++;
}

static int32_t dxc_buttons(unsigned int state) {
    return ((state & Button1Mask) ? 1 : 0) |
           ((state & Button3Mask) ? 2 : 0) |
           ((state & Button2Mask) ? 4 : 0);
}

// The shared Compose reader uses these four bits, as NSEvent does. Translate at the
// boundary so the same reader receives the same meaning on every desktop.
static int32_t dxc_modifiers(unsigned int state) {
    return ((state & ShiftMask) ? (1 << 17) : 0) |
           ((state & ControlMask) ? (1 << 18) : 0) |
           ((state & Mod1Mask) ? (1 << 19) : 0) |
           ((state & Mod4Mask) ? (1 << 20) : 0);
}

// The special key values are the shared reader's values. Printable keys also carry
// their code point, obtained from XLookupString for the current keyboard layout.
static int32_t dxc_key_code(KeySym key) {
    switch (key) {
        case XK_Return: case XK_KP_Enter: return 0x24;
        case XK_Tab: case XK_ISO_Left_Tab: return 0x30;
        case XK_space: return 0x31;
        case XK_BackSpace: return 0x33;
        case XK_Escape: return 0x35;
        case XK_Delete: return 0x75;
        case XK_Left: return 0x7B;
        case XK_Right: return 0x7C;
        case XK_Down: return 0x7D;
        case XK_Up: return 0x7E;
        case XK_Home: return 0x73;
        case XK_End: return 0x77;
        case XK_Prior: return 0x74;
        case XK_Next: return 0x79;
        default: return 0;
    }
}

/**
 * Tells the window manager that the drawing it was waiting for is done.
 *
 * Called where a frame ends, and where a frame turned out not to be needed: the manager
 * hands out the number with the resize and shows the frame it was holding when the counter
 * carries it, so every request it makes has to be answered by something.
 */
static void dxc_pay_sync(void) {
    if (!dxc_sync_owed || dxc_sync_counter == None || dxc_display == NULL) {
        return;
    }
    XSyncSetCounter(dxc_display, dxc_sync_counter, dxc_sync_value);
    dxc_sync_owed = 0;
    XFlush(dxc_display);
}

/**
 * Asks the renderer to draw a frame, now, where the need for it arose.
 *
 * Nothing here counts calls. A request that arrives while a frame is already being drawn
 * is refused on the renderer's side, which is where the scene and the surface are, and
 * where the refusal is something a test can watch.
 */
static void dxc_request_frame(void) {
    if (dxc_draw_frame == NULL || dxc_draw_frame(dxc_frame_thread) == 0) {
        // No frame came of it, either because there is no renderer to ask yet or because
        // one was already being drawn. A manager waiting on the counter must still be
        // answered: one that is never told holds the window until it gives up on it.
        dxc_pay_sync();
    }
}

/**
 * Hands this file the function that draws a frame, and the thread to call it on.
 *
 * Called once when the window opens and again with nothing when it closes, so that a
 * resize arriving while the scene is being taken down does not reach a scene that has
 * gone.
 */
void dxc_native_set_frame_callback(int32_t (*callback)(void *isolate_thread), void *isolate_thread) {
    dxc_draw_frame = callback;
    dxc_frame_thread = isolate_thread;
}

static void dxc_pump_events(void) {
    if (dxc_pumping) {
        return;
    }
    dxc_pumping = 1;
    while (dxc_display != NULL && XPending(dxc_display) > 0) {
        XEvent event;
        XNextEvent(dxc_display, &event);
        struct dxc_event record;
        memset(&record, 0, sizeof record);
        switch (event.type) {
            case MotionNotify:
                record.kind = DXC_EVENT_POINTER_MOVE;
                record.x = (float)event.xmotion.x;
                record.y = (float)event.xmotion.y;
                record.buttons = dxc_buttons(event.xmotion.state);
                record.modifiers = dxc_modifiers(event.xmotion.state);
                break;
            case ButtonPress:
            case ButtonRelease: {
                unsigned int button = event.xbutton.button;
                if (button >= 4 && button <= 7) {
                    if (event.type == ButtonRelease) continue;
                    record.kind = DXC_EVENT_SCROLL;
                    record.x = button == 6 ? -3.0f : button == 7 ? 3.0f : 0.0f;
                    record.y = button == 4 ? -3.0f : button == 5 ? 3.0f : 0.0f;
                } else {
                    record.kind = event.type == ButtonPress ? DXC_EVENT_POINTER_DOWN : DXC_EVENT_POINTER_UP;
                    record.x = (float)event.xbutton.x;
                    record.y = (float)event.xbutton.y;
                    record.buttons = dxc_buttons(event.xbutton.state);
                    int32_t bit = button == 1 ? 1 : button == 3 ? 2 : button == 2 ? 4 : 0;
                    if (event.type == ButtonPress) record.buttons |= bit;
                    else record.buttons &= ~bit;
                }
                record.modifiers = dxc_modifiers(event.xbutton.state);
                break;
            }
            case KeyPress:
            case KeyRelease: {
                KeySym symbol = NoSymbol;
                char bytes[32];
                int count = XLookupString(&event.xkey, bytes, sizeof bytes, &symbol, NULL);
                record.kind = event.type == KeyPress ? DXC_EVENT_KEY_DOWN : DXC_EVENT_KEY_UP;
                record.key_code = dxc_key_code(symbol);
                record.modifiers = dxc_modifiers(event.xkey.state);
                if (count == 1 && (unsigned char)bytes[0] >= 32) {
                    record.code_point = (unsigned char)bytes[0];
                }
                break;
            }
            case ConfigureNotify:
                if (event.xconfigure.width == dxc_width && event.xconfigure.height == dxc_height) {
                    // The window was moved, or told again what it already was. Nothing
                    // needs painting, and a manager waiting for a counter it asked about
                    // this change is told so here.
                    dxc_pay_sync();
                    continue;
                }
                dxc_width = event.xconfigure.width;
                dxc_height = event.xconfigure.height;
                // The frame is drawn here, inside the handling of the size change, rather
                // than written down for the next turn of the loop.
                //
                // When a hand drags an edge, the display server moves the window's frame
                // at once and what is inside the frame is whatever was last drawn. If the
                // two reach the screen in separate steps, a strip of the window has been
                // claimed and not yet painted, and the width of that strip is the speed of
                // the hand times how late the painting is. Measured on another desktop
                // where the same mistake was made: one screen refresh late at every speed,
                // which is 3 pixels for a slow drag and 350 for a fast one, while from
                // inside the process every frame looked on time and the right size.
                dxc_request_frame();
                continue;
            case Expose:
                // Whatever was covering the window has gone, and the copy the server kept
                // is not ours to trust. Nothing in the scene changed, so the frame loop
                // would draw nothing and a window uncovered on a server with no compositor
                // would keep showing what was in front of it. The last of a run of these
                // is enough: they arrive one per exposed rectangle and one frame paints
                // all of them.
                if (event.xexpose.count == 0) {
                    dxc_request_frame();
                }
                continue;
            case ClientMessage:
                if (event.xclient.message_type == dxc_protocols &&
                    (Atom)event.xclient.data.l[0] == dxc_sync_request) {
                    // The manager is about to resize the window and will hold the frame
                    // until the counter carries this number. It is set once the drawing
                    // for that size has been handed to the server, which is in
                    // dxc_native_frame_end.
                    XSyncIntsToValue(&dxc_sync_value,
                                     (unsigned int)event.xclient.data.l[2],
                                     (int)event.xclient.data.l[3]);
                    dxc_sync_owed = 1;
                    continue;
                }
                if ((Atom)event.xclient.data.l[0] == dxc_delete_window) dxc_closed = 1;
                continue;
            case DestroyNotify:
                dxc_closed = 1;
                continue;
            default:
                continue;
        }
        dxc_push_event(record);
    }
    dxc_pumping = 0;
}

// The oldest thing the window heard, or zero where it has heard nothing since the last
// turn. Reads nothing itself: what fills this queue is dxc_native_pump, and a second reader
// here would be a second place that could take a resize out of the queue mid-drag.
/*
 * The names below are answered here and do nothing.
 *
 * One piece of Kotlin drives every desktop and reaches their windows by name, so each of
 * these files answers every name, including the ones that mean nothing on it. A missing
 * one is a warning on the linkers that look names up at load time and a failure on the
 * ones that do not, which is a defect that travels to whoever builds for the strictest
 * platform. It travelled three times before this was written down.
 */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    (void)out;
    (void)capacity;
    return 0;
}

void dxc_native_clipboard_write(const char *text) {
    (void)text;
}

void dxc_native_install_menu(const char *application_name) {
    (void)application_name;
}

void dxc_native_set_draw_callback(void *callback, void *isolate_thread) {
    (void)callback;
    (void)isolate_thread;
}

int32_t dxc_native_poll_event(struct dxc_event *out) {
    if (dxc_event_count == 0) return 0;
    *out = dxc_events[dxc_event_head];
    dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    dxc_event_count--;
    return 1;
}

int32_t dxc_native_window_open(const char *title, int32_t width, int32_t height,
                               struct dxc_native_window *out) {
    memset(out, 0, sizeof *out);
    dxc_display = XOpenDisplay(NULL);
    if (dxc_display == NULL) return 1;
    int screen = DefaultScreen(dxc_display);
    int attributes[] = { GLX_RGBA, GLX_DOUBLEBUFFER, GLX_RED_SIZE, 8,
                         GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, None };
    XVisualInfo *visual = glXChooseVisual(dxc_display, screen, attributes);
    if (visual == NULL) { XCloseDisplay(dxc_display); dxc_display = NULL; return 2; }
    dxc_colormap = XCreateColormap(dxc_display, RootWindow(dxc_display, screen),
                                   visual->visual, AllocNone);
    XSetWindowAttributes settings;
    memset(&settings, 0, sizeof settings);
    settings.colormap = dxc_colormap;
    settings.event_mask = ExposureMask | StructureNotifyMask | PointerMotionMask |
                          ButtonPressMask | ButtonReleaseMask | KeyPressMask | KeyReleaseMask;
    dxc_window = XCreateWindow(dxc_display, RootWindow(dxc_display, screen), 0, 0,
                               (unsigned int)width, (unsigned int)height, 0, visual->depth,
                               InputOutput, visual->visual, CWColormap | CWEventMask, &settings);
    dxc_context = glXCreateContext(dxc_display, visual, NULL, True);
    XFree(visual);
    if (dxc_window == None || dxc_context == NULL ||
        !glXMakeCurrent(dxc_display, dxc_window, dxc_context)) {
        if (dxc_context != NULL) glXDestroyContext(dxc_display, dxc_context);
        if (dxc_window != None) XDestroyWindow(dxc_display, dxc_window);
        XFreeColormap(dxc_display, dxc_colormap);
        XCloseDisplay(dxc_display);
        dxc_display = NULL;
        return 3;
    }
    dxc_delete_window = XInternAtom(dxc_display, "WM_DELETE_WINDOW", False);
    dxc_protocols = XInternAtom(dxc_display, "WM_PROTOCOLS", False);
    dxc_sync_request = XInternAtom(dxc_display, "_NET_WM_SYNC_REQUEST", False);
    Atom protocols[2] = { dxc_delete_window, None };
    int protocol_count = 1;
    // Offered to the window manager before the window is mapped, because that is when the
    // manager reads what a window can do.
    //
    // A counter it can watch is the only way on this display server to have the drawing
    // reach the screen with the size change rather than after it: the manager asks for a
    // number with the resize, holds the new frame, and shows it when the counter says the
    // drawing for that size is done. A manager that does not offer this is not an error,
    // and nothing below depends on having one.
    int sync_event_base = 0;
    int sync_error_base = 0;
    int sync_major = 0;
    int sync_minor = 0;
    if (XSyncQueryExtension(dxc_display, &sync_event_base, &sync_error_base) &&
        XSyncInitialize(dxc_display, &sync_major, &sync_minor)) {
        XSyncValue start;
        XSyncIntToValue(&start, 0);
        dxc_sync_counter = XSyncCreateCounter(dxc_display, start);
        if (dxc_sync_counter != None) {
            Atom counter_property =
                XInternAtom(dxc_display, "_NET_WM_SYNC_REQUEST_COUNTER", False);
            // A property of format 32 is read out of an array of long, whatever a long is
            // on this machine. One entry is the basic protocol; a second would offer the
            // extended one, which asks to be told when each frame was actually shown.
            long counter_id = (long)dxc_sync_counter;
            XChangeProperty(dxc_display, dxc_window, counter_property, XA_CARDINAL, 32,
                            PropModeReplace, (unsigned char *)&counter_id, 1);
            protocols[protocol_count++] = dxc_sync_request;
        }
    }
    XSetWMProtocols(dxc_display, dxc_window, protocols, protocol_count);
    XStoreName(dxc_display, dxc_window, title);
    XMapWindow(dxc_display, dxc_window);
    XFlush(dxc_display);
    dxc_width = width;
    dxc_height = height;
    dxc_closed = 0;
    out->window = (void *)(uintptr_t)dxc_window;
    out->view = dxc_display;
    out->device = dxc_context;
    out->queue = dxc_display;
    out->layer = (void *)(uintptr_t)dxc_window;
    return 0;
}

// The size the window was last told it has, without reading anything.
//
// Reading events here is what it used to do, and it cannot: a frame drawn from inside a
// resize asks this, and taking the rest of that resize out of the queue in the middle of
// handling one of them is how a size change ends up recorded and never painted. Events are
// read in one place, which is dxc_native_pump.
void dxc_native_window_size(void *window_pointer, int32_t *width, int32_t *height, float *scale) {
    (void)window_pointer;
    *width = dxc_width;
    *height = dxc_height;
    *scale = 1.0f;
}

int32_t dxc_native_frame_begin(void *window_pointer) {
    (void)window_pointer;
    if (dxc_closed || dxc_width <= 0 || dxc_height <= 0) return 1;
    if (!glXMakeCurrent(dxc_display, dxc_window, dxc_context)) return 1;
    return 0;
}

void dxc_native_frame_end(void *display_pointer) {
    (void)display_pointer;
    if (dxc_closed) {
        return;
    }
    glXSwapBuffers(dxc_display, dxc_window);
    // After the swap, because what the manager is waiting to hear is that the drawing for
    // the size it gave us has been handed over. Until it hears that, it holds the frame it
    // was about to show, so the edge it moved and what is inside it appear together.
    dxc_pay_sync();
    // Out to the server before this returns. A swap sitting in the output buffer is a frame
    // nobody has been shown.
    XFlush(dxc_display);
}

/**
 * Lets the window answer for itself for a moment, and rests if it has nothing to say.
 *
 * Called once a frame. This thread is the one the display server's events are read on, so
 * the events of this frame arrive here or not at all.
 *
 * The waiting is here rather than in a sleep afterwards, and that is the point of it: a
 * resize that arrives while this is waiting is drawn inside the wait, in the same step
 * that recorded the new size, instead of a turn of the loop later. A window with nothing
 * happening rests on its connection for the frame's length rather than spinning.
 */
void dxc_native_pump(double seconds) {
    if (dxc_display == NULL) {
        return;
    }
    // XPending sends whatever is still in the output buffer before it answers, so the
    // frame just swapped is on its way out before this thread goes to sleep.
    if (seconds > 0.0 && XPending(dxc_display) == 0) {
        int connection = ConnectionNumber(dxc_display);
        fd_set readable;
        FD_ZERO(&readable);
        FD_SET(connection, &readable);
        struct timeval limit;
        limit.tv_sec = (time_t)seconds;
        limit.tv_usec = (suseconds_t)((seconds - (double)limit.tv_sec) * 1000000.0);
        select(connection + 1, &readable, NULL, NULL, &limit);
    }
    dxc_pump_events();
}

/** True once the reader has closed the window. */
int32_t dxc_native_window_closed(void) {
    return dxc_closed ? 1 : 0;
}

/**
 * Sets the shape of the pointer over the window.
 *
 * The scene decides what shape a thing under the pointer asks for and what crosses is a
 * number, so the names of this server's cursors are known only here. A shape this does not
 * have becomes the arrow, which is what a pointer over something unremarkable looks like.
 */
void dxc_native_set_cursor(int32_t shape) {
    static const unsigned int fonts[DXC_CURSOR_SHAPES] = {
        XC_left_ptr, XC_hand2, XC_xterm, XC_crosshair,
        XC_sb_h_double_arrow, XC_sb_v_double_arrow,
    };
    if (dxc_display == NULL || dxc_window == None) {
        return;
    }
    if (shape < 0 || shape >= DXC_CURSOR_SHAPES) {
        shape = 0;
    }
    // The scene asks on every crossing, and a pointer moving across a row of links asks
    // for the hand it already has. Answering that with a request to the server would be
    // traffic on the thread the frames are drawn from.
    if (shape == dxc_cursor_shape) {
        return;
    }
    if (dxc_cursors[shape] == None) {
        dxc_cursors[shape] = XCreateFontCursor(dxc_display, fonts[shape]);
    }
    XDefineCursor(dxc_display, dxc_window, dxc_cursors[shape]);
    dxc_cursor_shape = shape;
    XFlush(dxc_display);
}

/**
 * Takes the tree the window would tell a reader who cannot see it.
 *
 * Copied and kept, not published. What answers an assistive technology on this desktop is
 * AT-SPI, which is a bus, an interface and a registration of its own and is not written
 * yet. Holding the newest tree is the half of it that belongs to the window: when that
 * work arrives it reads from here rather than asking the scene across a thread it is not
 * on, which is the one thing a frame loop cannot afford.
 */
void dxc_native_set_accessibility(const struct dxc_element *elements, int32_t count,
                                  void *window_pointer) {
    (void)window_pointer;
    if (count < 0) {
        count = 0;
    }
    if (count > DXC_ELEMENT_CAPACITY) {
        count = DXC_ELEMENT_CAPACITY;
    }
    if (count > 0) {
        memcpy(dxc_elements, elements, (size_t)count * sizeof *dxc_elements);
    }
    dxc_element_count = count;
    if (getenv("DXC_REPORT_FRAMES") != NULL) {
        fprintf(stderr, "dioxus-compose: the window holds %d things to say", count);
        if (count > 0) {
            fprintf(stderr, ", the first being \"%s\"", dxc_elements[0].label);
        }
        fprintf(stderr, "\n");
    }
}
