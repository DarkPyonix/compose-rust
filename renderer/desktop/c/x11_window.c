// An X11 window and GLX framebuffer owned by the renderer. XWayland accepts the same
// X11 connection on Wayland desktops. Skia paints; this file only presents and records.
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/cursorfont.h>
#include <X11/keysym.h>
#include <X11/Xresource.h>
#include <X11/extensions/sync.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <locale.h>
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
    // Text the input method finished, and text it is still composing.
    DXC_EVENT_TEXT_COMMIT = 7,
    DXC_EVENT_TEXT_COMPOSE = 8,
    // Files over the window, let go on it, and gone from it without being let go.
    DXC_EVENT_FILES_ENTERED = 10,
    DXC_EVENT_FILES_DROPPED = 11,
    DXC_EVENT_FILES_EXITED = 12,
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

// What the application asked of its window, held until the window is made.
static struct {
    int32_t resizable;
    int32_t min_width;
    int32_t min_height;
    int32_t system_chrome;
    int32_t backdrop;
} dxc_options = {1, 0, 0, 0, 0};

// How many pixels go to a point on this display, read once when the window is made.
static float dxc_scale = 1.0f;

// Atoms this file asks the server for once.
static Atom dxc_a_clipboard, dxc_a_primary, dxc_a_utf8, dxc_a_targets, dxc_a_text,
    dxc_a_property, dxc_a_net_wm_name, dxc_a_net_wm_icon, dxc_a_net_wm_state,
    dxc_a_maximized_vert, dxc_a_maximized_horz, dxc_a_moveresize, dxc_a_active_window,
    dxc_a_motif_hints, dxc_a_incr;
static Atom dxc_a_xdnd_aware, dxc_a_xdnd_enter, dxc_a_xdnd_position, dxc_a_xdnd_status,
    dxc_a_xdnd_leave, dxc_a_xdnd_drop, dxc_a_xdnd_finished, dxc_a_xdnd_selection,
    dxc_a_xdnd_copy, dxc_a_xdnd_type_list, dxc_a_uri_list;

// The text this window put on the clipboard, which it answers for while it owns it.
static char *dxc_clip_text;
static size_t dxc_clip_length;
static int dxc_owns_clipboard;

// Set from any thread to ask the window to come forward; acted on in the next turn, which
// is on the thread Xlib is used from.
static volatile int dxc_raise_requested;

// The input method and the context made from it, and the text being composed in it.
static XIM dxc_im;
static XIC dxc_ic;
#define DXC_PREEDIT_CAPACITY 256
static wchar_t dxc_preedit[DXC_PREEDIT_CAPACITY];
static int dxc_preedit_length;

// Files being dragged over the window and the ones last let go on it.
#define DXC_DROPPED_BYTES (64 * 1024)
static char dxc_dropped_paths[DXC_DROPPED_BYTES];
static int32_t dxc_dropped_length;
static Window dxc_drag_source;
static int dxc_drag_has_files;
static Time dxc_drop_time;
// Where the last XdndPosition said the files were, in root coordinates.
static int dxc_drag_root_x, dxc_drag_root_y;

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

// The key numbers are the shared reader's, which are the macOS ones: the letters and digits
// by where they sit on a board, and the special keys by name. A key with no number answers
// -1, never 0, because 0 is the A key and a shortcut that does not map would otherwise arrive
// as ctrl and A.
//
// Printable keys also carry their code point, obtained from XLookupString for the current
// keyboard layout.
static int32_t dxc_key_code(KeySym key) {
    static const int32_t letters[26] = {
        0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
        0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06,
    };
    static const int32_t digits[10] = {
        0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19,
    };
    if (key >= XK_a && key <= XK_z) return letters[key - XK_a];
    if (key >= XK_A && key <= XK_Z) return letters[key - XK_A];
    if (key >= XK_0 && key <= XK_9) return digits[key - XK_0];
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
        default: return -1;
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

/* Encodes one code point as UTF-8 and answers how many bytes that was. */
static int dxc_encode_utf8(unsigned long code, char *out) {
    if (code < 0x80) { out[0] = (char)code; return 1; }
    if (code < 0x800) {
        out[0] = (char)(0xC0 | (code >> 6)); out[1] = (char)(0x80 | (code & 0x3F)); return 2;
    }
    if (code < 0x10000) {
        out[0] = (char)(0xE0 | (code >> 12)); out[1] = (char)(0x80 | ((code >> 6) & 0x3F));
        out[2] = (char)(0x80 | (code & 0x3F)); return 3;
    }
    out[0] = (char)(0xF0 | (code >> 18)); out[1] = (char)(0x80 | ((code >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((code >> 6) & 0x3F)); out[3] = (char)(0x80 | (code & 0x3F));
    return 4;
}

/* Pushes text as an event, cut at a character boundary where it does not fit. */
static void dxc_push_text(int32_t kind, const char *utf8, size_t length) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    size_t take = length < DXC_TEXT_BYTES - 1 ? length : DXC_TEXT_BYTES - 1;
    while (take > 0 && take < length && ((unsigned char)utf8[take] & 0xC0) == 0x80) {
        take--;
    }
    memcpy(record.text, utf8, take);
    dxc_push_event(record);
}

/* What is being composed, as the whole run every time, which is what the scene is given. */
static void dxc_push_preedit(void) {
    char text[DXC_PREEDIT_CAPACITY * 4 + 1];
    size_t used = 0;
    for (int index = 0; index < dxc_preedit_length; index++) {
        used += (size_t)dxc_encode_utf8((unsigned long)dxc_preedit[index], text + used);
    }
    dxc_push_text(DXC_EVENT_TEXT_COMPOSE, text, used);
}

/*
 * The input method's preedit callbacks. The method says which run of characters changed
 * and what they are now, counted in characters as the protocol counts them, and the whole
 * run is kept here and handed to the scene each time.
 */
static int dxc_preedit_start(XIC ic, XPointer client, XPointer call) {
    (void)ic; (void)client; (void)call;
    dxc_preedit_length = 0;
    // No limit on the length of what is composed. The method reads this return value, and
    // a function that returns nothing would leave whatever was in the register.
    return -1;
}

static void dxc_preedit_draw(XIC ic, XPointer client, XIMPreeditDrawCallbackStruct *draw) {
    (void)ic; (void)client;
    if (draw == NULL) return;
    int first = draw->chg_first;
    int removed = draw->chg_length;
    if (first < 0) first = 0;
    if (first > dxc_preedit_length) first = dxc_preedit_length;
    if (removed < 0) removed = 0;
    if (first + removed > dxc_preedit_length) removed = dxc_preedit_length - first;

    wchar_t added[DXC_PREEDIT_CAPACITY];
    int added_length = 0;
    XIMText *text = draw->text;
    if (text != NULL) {
        if (text->encoding_is_wchar && text->string.wide_char != NULL) {
            for (int i = 0; i < (int)text->length && added_length < DXC_PREEDIT_CAPACITY; i++) {
                added[added_length++] = text->string.wide_char[i];
            }
        } else if (text->string.multi_byte != NULL) {
            size_t count = mbstowcs(added, text->string.multi_byte, DXC_PREEDIT_CAPACITY);
            if (count != (size_t)-1) added_length = (int)count;
        }
    }
    int tail = dxc_preedit_length - first - removed;
    if (first + added_length + tail > DXC_PREEDIT_CAPACITY) {
        added_length = DXC_PREEDIT_CAPACITY - first - tail;
        if (added_length < 0) return;
    }
    memmove(dxc_preedit + first + added_length, dxc_preedit + first + removed,
            (size_t)tail * sizeof(wchar_t));
    memcpy(dxc_preedit + first, added, (size_t)added_length * sizeof(wchar_t));
    dxc_preedit_length = first + added_length + tail;
    dxc_push_preedit();
}

static void dxc_preedit_done(XIC ic, XPointer client, XPointer call) {
    (void)ic; (void)client; (void)call;
    if (dxc_preedit_length > 0) {
        dxc_preedit_length = 0;
        dxc_push_preedit();
    }
}

static void dxc_preedit_caret(XIC ic, XPointer client, XPointer call) {
    (void)ic; (void)client; (void)call;
}

static XIMCallback dxc_cb_start, dxc_cb_draw, dxc_cb_done, dxc_cb_caret;

/* Opens the desktop's input method and a context on the window. None is not an error. */
static void dxc_open_input_method(void) {
    setlocale(LC_CTYPE, "");
    if (!XSupportsLocale()) return;
    XSetLocaleModifiers("");
    dxc_im = XOpenIM(dxc_display, NULL, NULL, NULL);
    if (dxc_im == NULL) return;
    XIMStyles *styles = NULL;
    if (XGetIMValues(dxc_im, XNQueryInputStyle, &styles, NULL) != NULL || styles == NULL) {
        XCloseIM(dxc_im); dxc_im = NULL; return;
    }
    XIMStyle callbacks = XIMPreeditCallbacks | XIMStatusNothing;
    XIMStyle plain = XIMPreeditNothing | XIMStatusNothing;
    XIMStyle none = XIMPreeditNone | XIMStatusNone;
    XIMStyle chosen = 0;
    int have_callbacks = 0, have_plain = 0, have_none = 0;
    for (int i = 0; i < styles->count_styles; i++) {
        if (styles->supported_styles[i] == callbacks) have_callbacks = 1;
        if (styles->supported_styles[i] == plain) have_plain = 1;
        if (styles->supported_styles[i] == none) have_none = 1;
    }
    XFree(styles);
    chosen = have_callbacks ? callbacks : have_plain ? plain : have_none ? none : 0;
    if (chosen == 0) { XCloseIM(dxc_im); dxc_im = NULL; return; }
    if (chosen == callbacks) {
        dxc_cb_start.client_data = NULL; dxc_cb_start.callback = (XIMProc)dxc_preedit_start;
        dxc_cb_draw.client_data = NULL;  dxc_cb_draw.callback = (XIMProc)dxc_preedit_draw;
        dxc_cb_done.client_data = NULL;  dxc_cb_done.callback = (XIMProc)dxc_preedit_done;
        dxc_cb_caret.client_data = NULL; dxc_cb_caret.callback = (XIMProc)dxc_preedit_caret;
        XVaNestedList list = XVaCreateNestedList(0,
            XNPreeditStartCallback, &dxc_cb_start, XNPreeditDrawCallback, &dxc_cb_draw,
            XNPreeditDoneCallback, &dxc_cb_done, XNPreeditCaretCallback, &dxc_cb_caret, NULL);
        dxc_ic = XCreateIC(dxc_im, XNInputStyle, chosen, XNClientWindow, dxc_window,
                           XNFocusWindow, dxc_window, XNPreeditAttributes, list, NULL);
        XFree(list);
    } else {
        dxc_ic = XCreateIC(dxc_im, XNInputStyle, chosen, XNClientWindow, dxc_window,
                           XNFocusWindow, dxc_window, NULL);
    }
    if (dxc_ic == NULL) { XCloseIM(dxc_im); dxc_im = NULL; }
}

/** Where the caret is, in pixels from the window's top left, for the candidate window. */
void dxc_native_set_ime_spot(float x, float y) {
    if (dxc_ic == NULL) return;
    XPoint spot;
    spot.x = (short)x;
    spot.y = (short)y;
    XVaNestedList list = XVaCreateNestedList(0, XNSpotLocation, &spot, NULL);
    XSetICValues(dxc_ic, XNPreeditAttributes, list, NULL);
    XFree(list);
}

/* The bytes of a text property, or null. */
static unsigned char *dxc_read_property(Window window, Atom property, unsigned long *length,
                                        Atom *type) {
    Atom actual = None;
    int format = 0;
    unsigned long count = 0, after = 0;
    unsigned char *data = NULL;
    if (XGetWindowProperty(dxc_display, window, property, 0, 1 << 20, True, AnyPropertyType,
                           &actual, &format, &count, &after, &data) != Success) {
        return NULL;
    }
    *length = count * (unsigned long)(format / 8);
    *type = actual;
    return data;
}

/** The clipboard's text as UTF-8 copied into [out], and its length; zero where none. */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    if (dxc_display == NULL) return 0;
    if (dxc_owns_clipboard && dxc_clip_text != NULL) {
        if ((int32_t)dxc_clip_length > capacity) return 0;
        memcpy(out, dxc_clip_text, dxc_clip_length);
        return (int32_t)dxc_clip_length;
    }
    XConvertSelection(dxc_display, dxc_a_clipboard, dxc_a_utf8, dxc_a_property, dxc_window,
                      CurrentTime);
    XFlush(dxc_display);
    // The owner answers with an event, which this waits for without reading the others:
    // they stay queued for the turn of the loop that handles them.
    for (int attempt = 0; attempt < 100; attempt++) {
        XEvent reply;
        if (XCheckTypedWindowEvent(dxc_display, dxc_window, SelectionNotify, &reply)) {
            if (reply.xselection.property == None) return 0;
            unsigned long length = 0;
            Atom type = None;
            unsigned char *data = dxc_read_property(dxc_window, reply.xselection.property,
                                                    &length, &type);
            if (data == NULL) return 0;
            int32_t copied = 0;
            if (type != dxc_a_incr && (int32_t)length <= capacity) {
                memcpy(out, data, length);
                copied = (int32_t)length;
            }
            XFree(data);
            return copied;
        }
        struct timeval wait = {0, 5000};
        fd_set readable;
        FD_ZERO(&readable);
        FD_SET(ConnectionNumber(dxc_display), &readable);
        select(ConnectionNumber(dxc_display) + 1, &readable, NULL, NULL, &wait);
    }
    return 0;
}

/** Puts [text] on the clipboard and the primary selection, and answers for them. */
void dxc_native_clipboard_write(const char *text) {
    if (dxc_display == NULL || text == NULL) return;
    free(dxc_clip_text);
    dxc_clip_length = strlen(text);
    dxc_clip_text = (char *)malloc(dxc_clip_length + 1);
    if (dxc_clip_text == NULL) { dxc_clip_length = 0; return; }
    memcpy(dxc_clip_text, text, dxc_clip_length + 1);
    XSetSelectionOwner(dxc_display, dxc_a_clipboard, dxc_window, CurrentTime);
    XSetSelectionOwner(dxc_display, dxc_a_primary, dxc_window, CurrentTime);
    dxc_owns_clipboard = 1;
    XFlush(dxc_display);
}

/* Answers another program asking for what this window put on a selection. */
static void dxc_answer_selection_request(XSelectionRequestEvent *request) {
    XEvent reply;
    memset(&reply, 0, sizeof reply);
    reply.xselection.type = SelectionNotify;
    reply.xselection.requestor = request->requestor;
    reply.xselection.selection = request->selection;
    reply.xselection.target = request->target;
    reply.xselection.time = request->time;
    reply.xselection.property = None;
    Atom property = request->property != None ? request->property : request->target;
    if (dxc_clip_text != NULL) {
        if (request->target == dxc_a_targets) {
            Atom targets[] = { dxc_a_targets, dxc_a_utf8, XA_STRING, dxc_a_text };
            XChangeProperty(dxc_display, request->requestor, property, XA_ATOM, 32,
                            PropModeReplace, (unsigned char *)targets, 4);
            reply.xselection.property = property;
        } else if (request->target == dxc_a_utf8 || request->target == XA_STRING ||
                   request->target == dxc_a_text) {
            XChangeProperty(dxc_display, request->requestor, property,
                            request->target == XA_STRING ? XA_STRING : dxc_a_utf8, 8,
                            PropModeReplace, (unsigned char *)dxc_clip_text,
                            (int)dxc_clip_length);
            reply.xselection.property = property;
        }
    }
    XSendEvent(dxc_display, request->requestor, False, NoEventMask, &reply);
    XFlush(dxc_display);
}

/** The paths of the files last dragged over the window, NUL between them, and the length. */
int32_t dxc_native_dropped_paths(char *out, int32_t capacity) {
    if (dxc_dropped_length <= 0 || dxc_dropped_length > capacity) return 0;
    memcpy(out, dxc_dropped_paths, (size_t)dxc_dropped_length);
    return dxc_dropped_length;
}

/* Turns the uri list a drag carries into the paths in it. */
static void dxc_read_uri_list(const char *list, unsigned long length) {
    int32_t used = 0;
    unsigned long at = 0;
    while (at < length) {
        unsigned long end = at;
        while (end < length && list[end] != '\r' && list[end] != '\n') end++;
        if (end - at > 7 && strncmp(list + at, "file://", 7) == 0) {
            unsigned long from = at + 7;
            // A host name between the slashes is skipped; the path begins at the next one.
            while (from < end && list[from] != '/') from++;
            int32_t start = used;
            if (used > 0) {
                if (used + 1 >= DXC_DROPPED_BYTES) break;
                dxc_dropped_paths[used++] = '\0';
                start = used;
            }
            for (unsigned long i = from; i < end && used < DXC_DROPPED_BYTES - 1; i++) {
                char c = list[i];
                if (c == '%' && i + 2 < end) {
                    char hex[3] = { list[i + 1], list[i + 2], 0 };
                    char *stop = NULL;
                    long value = strtol(hex, &stop, 16);
                    if (stop != NULL && *stop == '\0') {
                        dxc_dropped_paths[used++] = (char)value;
                        i += 2;
                        continue;
                    }
                }
                dxc_dropped_paths[used++] = c;
            }
            if (used == start) used = start > 0 ? start - 1 : 0;
        }
        at = end;
        while (at < length && (list[at] == '\r' || list[at] == '\n')) at++;
    }
    dxc_dropped_length = used;
}

static void dxc_push_drag(int32_t kind, int root_x, int root_y) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    int x = 0, y = 0;
    Window child;
    XTranslateCoordinates(dxc_display, DefaultRootWindow(dxc_display), dxc_window,
                          root_x, root_y, &x, &y, &child);
    record.x = (float)x;
    record.y = (float)y;
    dxc_push_event(record);
}

static void dxc_send_dnd(Atom type, long a, long b, long c, long d) {
    XEvent message;
    memset(&message, 0, sizeof message);
    message.xclient.type = ClientMessage;
    message.xclient.window = dxc_drag_source;
    message.xclient.message_type = type;
    message.xclient.format = 32;
    message.xclient.data.l[0] = (long)dxc_window;
    message.xclient.data.l[1] = a;
    message.xclient.data.l[2] = b;
    message.xclient.data.l[3] = c;
    message.xclient.data.l[4] = d;
    XSendEvent(dxc_display, dxc_drag_source, False, NoEventMask, &message);
    XFlush(dxc_display);
}

/* Handles the messages of the drag and drop protocol. Answers true where it was one. */
static int dxc_handle_dnd(XClientMessageEvent *message) {
    Atom type = message->message_type;
    if (type == dxc_a_xdnd_enter) {
        dxc_drag_source = (Window)message->data.l[0];
        int more = (message->data.l[1] & 1) != 0;
        dxc_drag_has_files = 0;
        if (!more) {
            for (int i = 2; i < 5; i++) {
                if ((Atom)message->data.l[i] == dxc_a_uri_list) dxc_drag_has_files = 1;
            }
        } else {
            unsigned long length = 0;
            Atom actual = None;
            unsigned char *data = dxc_read_property(dxc_drag_source, dxc_a_xdnd_type_list,
                                                    &length, &actual);
            if (data != NULL) {
                // Read as the format 32 array it is: an array of long, whatever a long is.
                long *types = (long *)data;
                for (unsigned long i = 0; i < length / 4; i++) {
                    if ((Atom)types[i] == dxc_a_uri_list) dxc_drag_has_files = 1;
                }
                XFree(data);
            }
        }
        return 1;
    }
    if (type == dxc_a_xdnd_position) {
        int root_x = (int)((message->data.l[2] >> 16) & 0xFFFF);
        int root_y = (int)(message->data.l[2] & 0xFFFF);
        dxc_drag_root_x = root_x;
        dxc_drag_root_y = root_y;
        if (dxc_drag_has_files) dxc_push_drag(DXC_EVENT_FILES_ENTERED, root_x, root_y);
        dxc_send_dnd(dxc_a_xdnd_status, dxc_drag_has_files ? 1 : 0, 0, 0,
                     dxc_drag_has_files ? (long)dxc_a_xdnd_copy : 0);
        return 1;
    }
    if (type == dxc_a_xdnd_leave) {
        if (dxc_drag_has_files) {
            struct dxc_event record;
            memset(&record, 0, sizeof record);
            record.kind = DXC_EVENT_FILES_EXITED;
            dxc_push_event(record);
        }
        dxc_drag_has_files = 0;
        return 1;
    }
    if (type == dxc_a_xdnd_drop) {
        dxc_drop_time = (Time)message->data.l[2];
        if (dxc_drag_has_files) {
            XConvertSelection(dxc_display, dxc_a_xdnd_selection, dxc_a_uri_list,
                              dxc_a_property, dxc_window, dxc_drop_time);
        } else {
            dxc_send_dnd(dxc_a_xdnd_finished, 0, 0, 0, 0);
        }
        return 1;
    }
    return 0;
}

/* The files arrived: take their paths, say where they were let go, and tell the source. */
static void dxc_finish_drop(XSelectionEvent *arrived) {
    int ok = 0;
    if (arrived->property != None) {
        unsigned long length = 0;
        Atom type = None;
        unsigned char *data = dxc_read_property(dxc_window, arrived->property, &length, &type);
        if (data != NULL) {
            dxc_read_uri_list((const char *)data, length);
            XFree(data);
            ok = dxc_dropped_length > 0;
        }
    }
    if (ok) {
        // Where the files were when they were let go, as the last position said, rather than
        // where the pointer is by the time this is read.
        dxc_push_drag(DXC_EVENT_FILES_DROPPED, dxc_drag_root_x, dxc_drag_root_y);
    }
    dxc_send_dnd(dxc_a_xdnd_finished, ok ? 1 : 0, ok ? (long)dxc_a_xdnd_copy : 0, 0, 0);
    dxc_drag_has_files = 0;
}

/**
 * Does with the window what a button of the application's own caption asks: 0 minimises,
 * 1 maximises or restores, 2 closes, 3 brings it forward. Safe from any thread for the
 * last one only, which is a flag acted on in the next turn.
 */
void dxc_native_window_action(int32_t action) {
    if (action == 3) {
        dxc_raise_requested = 1;
        return;
    }
    if (dxc_display == NULL || dxc_window == None) return;
    switch (action) {
    case 0:
        XIconifyWindow(dxc_display, dxc_window, DefaultScreen(dxc_display));
        break;
    case 1: {
        XEvent message;
        memset(&message, 0, sizeof message);
        message.xclient.type = ClientMessage;
        message.xclient.window = dxc_window;
        message.xclient.message_type = dxc_a_net_wm_state;
        message.xclient.format = 32;
        message.xclient.data.l[0] = 2; /* toggle */
        message.xclient.data.l[1] = (long)dxc_a_maximized_vert;
        message.xclient.data.l[2] = (long)dxc_a_maximized_horz;
        message.xclient.data.l[3] = 1;
        XSendEvent(dxc_display, DefaultRootWindow(dxc_display), False,
                   SubstructureNotifyMask | SubstructureRedirectMask, &message);
        break;
    }
    case 2:
        dxc_closed = 1;
        break;
    default:
        break;
    }
    XFlush(dxc_display);
}

/**
 * Hands the move or the resize to the window manager: 0 moves, and 1 to 8 pull the left,
 * right, top and bottom edges and the four corners in that order.
 */
void dxc_native_window_begin_drag(int32_t edge) {
    if (dxc_display == NULL || dxc_window == None) return;
    // The directions _NET_WM_MOVERESIZE names, indexed by the edge numbers above.
    static const long direction[9] = { 8, 7, 3, 1, 5, 0, 2, 6, 4 };
    if (edge < 0 || edge > 8) return;
    Window root, child;
    int root_x = 0, root_y = 0, x = 0, y = 0;
    unsigned int mask = 0;
    XQueryPointer(dxc_display, dxc_window, &root, &child, &root_x, &root_y, &x, &y, &mask);
    XUngrabPointer(dxc_display, CurrentTime);
    XEvent message;
    memset(&message, 0, sizeof message);
    message.xclient.type = ClientMessage;
    message.xclient.window = dxc_window;
    message.xclient.message_type = dxc_a_moveresize;
    message.xclient.format = 32;
    message.xclient.data.l[0] = root_x;
    message.xclient.data.l[1] = root_y;
    message.xclient.data.l[2] = direction[edge];
    message.xclient.data.l[3] = Button1;
    message.xclient.data.l[4] = 1;
    XSendEvent(dxc_display, DefaultRootWindow(dxc_display), False,
               SubstructureNotifyMask | SubstructureRedirectMask, &message);
    XFlush(dxc_display);
    // The manager owns the pointer from here, so the release that ends the press is never
    // delivered to this window. The scene is told it happened or it would believe the
    // button is still down.
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_POINTER_UP;
    record.x = (float)x;
    record.y = (float)y;
    dxc_push_event(record);
}

/** Reads how many pixels go to a point: the toolkit-independent settings desktops write. */
static float dxc_detect_scale(void) {
    const char *forced = getenv("GDK_SCALE");
    if (forced != NULL && atof(forced) >= 1.0) return (float)atof(forced);
    char *resources = XResourceManagerString(dxc_display);
    if (resources != NULL) {
        XrmInitialize();
        XrmDatabase database = XrmGetStringDatabase(resources);
        if (database != NULL) {
            char *type = NULL;
            XrmValue value;
            if (XrmGetResource(database, "Xft.dpi", "Xft.Dpi", &type, &value) && value.addr != NULL) {
                double dpi = atof(value.addr);
                if (dpi >= 96.0) {
                    XrmDestroyDatabase(database);
                    return (float)(dpi / 96.0);
                }
            }
            XrmDestroyDatabase(database);
        }
    }
    return 1.0f;
}

static void dxc_pump_events(void) {
    if (dxc_pumping) {
        return;
    }
    dxc_pumping = 1;
    if (dxc_raise_requested && dxc_display != NULL) {
        dxc_raise_requested = 0;
        XMapRaised(dxc_display, dxc_window);
        XEvent message;
        memset(&message, 0, sizeof message);
        message.xclient.type = ClientMessage;
        message.xclient.window = dxc_window;
        message.xclient.message_type = dxc_a_active_window;
        message.xclient.format = 32;
        message.xclient.data.l[0] = 1;
        XSendEvent(dxc_display, DefaultRootWindow(dxc_display), False,
                   SubstructureNotifyMask | SubstructureRedirectMask, &message);
        XFlush(dxc_display);
    }
    while (dxc_display != NULL && XPending(dxc_display) > 0) {
        XEvent event;
        XNextEvent(dxc_display, &event);
        // Offered to the input method first. A key it takes is part of a composition and
        // is not handled twice.
        if (XFilterEvent(&event, None)) {
            continue;
        }
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
                    if (event.type == ButtonPress && dxc_ic != NULL && dxc_preedit_length > 0) {
                        // A click while composing ends the composition and keeps what was
                        // typed, which is what the other desktops' input methods do.
                        char *kept = Xutf8ResetIC(dxc_ic);
                        dxc_preedit_length = 0;
                        dxc_push_preedit();
                        if (kept != NULL) {
                            dxc_push_text(DXC_EVENT_TEXT_COMMIT, kept, strlen(kept));
                            XFree(kept);
                        }
                    }
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
                char bytes[64];
                int count = 0;
                Status status = XLookupNone;
                if (event.type == KeyPress && dxc_ic != NULL) {
                    count = Xutf8LookupString(dxc_ic, &event.xkey, bytes, (int)sizeof bytes - 1,
                                              &symbol, &status);
                    if (status == XBufferOverflow) count = 0;
                } else {
                    count = XLookupString(&event.xkey, bytes, (int)sizeof bytes - 1, &symbol, NULL);
                    status = count > 0 ? XLookupChars : XLookupKeySym;
                    if (count > 0 && (unsigned char)bytes[0] >= 0x80) {
                        // Latin-1 from the plain lookup, re-encoded as the UTF-8 text is.
                        char converted[8];
                        int length = dxc_encode_utf8((unsigned char)bytes[0], converted);
                        memcpy(bytes, converted, (size_t)length);
                        count = length;
                    }
                }
                record.kind = event.type == KeyPress ? DXC_EVENT_KEY_DOWN : DXC_EVENT_KEY_UP;
                record.key_code = dxc_key_code(symbol);
                record.modifiers = dxc_modifiers(event.xkey.state);
                if (count == 1 && (unsigned char)bytes[0] >= 32 && (unsigned char)bytes[0] < 127) {
                    record.code_point = (unsigned char)bytes[0];
                }
                // The key is recorded first and its text after, the order the other desktops
                // use. A shortcut types nothing: control, alt and the super key held mean
                // the key is a command.
                if (event.type == KeyPress && count > 0 &&
                    (event.xkey.state & (ControlMask | Mod1Mask | Mod4Mask)) == 0 &&
                    (unsigned char)bytes[0] >= 0x20 && bytes[0] != 0x7f) {
                    dxc_push_event(record);
                    dxc_push_text(DXC_EVENT_TEXT_COMMIT, bytes, (size_t)count);
                    continue;
                }
                break;
            }
            case FocusIn:
                if (dxc_ic != NULL) XSetICFocus(dxc_ic);
                continue;
            case FocusOut:
                if (dxc_ic != NULL) XUnsetICFocus(dxc_ic);
                continue;
            case SelectionRequest:
                dxc_answer_selection_request(&event.xselectionrequest);
                continue;
            case SelectionClear:
                dxc_owns_clipboard = 0;
                continue;
            case SelectionNotify:
                if (event.xselection.selection == dxc_a_xdnd_selection) {
                    dxc_finish_drop(&event.xselection);
                }
                continue;
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
                if (dxc_handle_dnd(&event.xclient)) {
                    continue;
                }
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
void dxc_native_install_menu(const char *application_name) {
    (void)application_name;
}

/*
 * Measuring aids of the AppKit window, named because the Kotlin that drives every desktop
 * names them and answered here by doing nothing: a drag cannot be scripted from inside
 * this window, and nothing asks for it to be.
 */
void dxc_native_debug_resize(void *window_pointer, void *view_pointer, int32_t from_width,
                             int32_t from_height, int32_t to_width, int32_t to_height,
                             int32_t steps, int32_t pause_micros) {
    (void)window_pointer; (void)view_pointer; (void)from_width; (void)from_height;
    (void)to_width; (void)to_height; (void)steps; (void)pause_micros;
}

void dxc_native_debug_key(void *window_pointer, int32_t key_code, const char *characters) {
    (void)window_pointer; (void)key_code; (void)characters;
}

/**
 * Says how the next window should be made. Called once, before it is opened.
 *
 * `system_chrome` keeps the window manager's frame. Without it the frame is taken off and
 * the renderer draws the caption and the edges. `backdrop` means nothing here: this window
 * has no material to put behind the page.
 */
void dxc_native_window_configure(
    int32_t resizable,
    int32_t min_width,
    int32_t min_height,
    int32_t system_chrome,
    int32_t backdrop
) {
    dxc_options.resizable = resizable;
    dxc_options.min_width = min_width;
    dxc_options.min_height = min_height;
    dxc_options.system_chrome = system_chrome;
    dxc_options.backdrop = backdrop;
}

void dxc_native_window_caption(void *view_pointer, float *height, float *buttons_width) {
    (void)view_pointer;
    *height = 0;
    *buttons_width = 0;
}

/**
 * Gives the window the picture it named, from its pixels: eight bits each of red, green,
 * blue and alpha, the colour already multiplied by the alpha, row after row.
 */
void dxc_native_set_icon(const uint8_t *rgba, int32_t width, int32_t height) {
    if (dxc_display == NULL || dxc_window == None || rgba == NULL || width <= 0 || height <= 0) {
        return;
    }
    size_t pixels = (size_t)width * (size_t)height;
    // An array of long, whatever a long is: the width, the height, then one value a pixel
    // with alpha in the top byte and the colour straight rather than multiplied.
    unsigned long *data = (unsigned long *)malloc((pixels + 2) * sizeof *data);
    if (data == NULL) return;
    data[0] = (unsigned long)width;
    data[1] = (unsigned long)height;
    for (size_t index = 0; index < pixels; index++) {
        unsigned long r = rgba[index * 4 + 0], g = rgba[index * 4 + 1];
        unsigned long b = rgba[index * 4 + 2], a = rgba[index * 4 + 3];
        if (a != 0 && a != 255) {
            r = (r * 255 + a / 2) / a; g = (g * 255 + a / 2) / a; b = (b * 255 + a / 2) / a;
            if (r > 255) r = 255;
            if (g > 255) g = 255;
            if (b > 255) b = 255;
        }
        data[index + 2] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    XChangeProperty(dxc_display, dxc_window, dxc_a_net_wm_icon, XA_CARDINAL, 32, PropModeReplace,
                    (unsigned char *)data, (int)(pixels + 2));
    free(data);
    XFlush(dxc_display);
}

void dxc_native_set_draw_callback(void *callback, void *isolate_thread) {
    (void)callback;
    (void)isolate_thread;
}

/*
 * Notifications, answered here and doing nothing. On this desktop the renderer speaks to
 * the notification daemon over D-Bus from Kotlin, so the C names the macOS and Windows
 * builds use for their notification centres are never called. They are here because every
 * desktop answers every name.
 */
int32_t dxc_notify_start(void) {
    return 4; /* unsupported through this path */
}

void dxc_notify_request_permission(void) {}

void dxc_notify_refresh_permission(void) {}

void dxc_notify_post(const char *key, const char *title, const char *body, const char *channel,
                     const char *action1, const char *action2, int32_t urgent) {
    (void)key;
    (void)title;
    (void)body;
    (void)channel;
    (void)action1;
    (void)action2;
    (void)urgent;
}

void dxc_notify_withdraw(const char *key) {
    (void)key;
}

void dxc_notify_withdraw_all(void) {}

int32_t dxc_notify_next_event(char *key, int32_t capacity, int32_t *value) {
    (void)key;
    (void)capacity;
    (void)value;
    return 0;
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
    // The size asked for is in points; the window is made in the pixels they come to on
    // this display.
    dxc_scale = dxc_detect_scale();
    width = (int32_t)((float)width * dxc_scale + 0.5f);
    height = (int32_t)((float)height * dxc_scale + 0.5f);
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
                          ButtonPressMask | ButtonReleaseMask | KeyPressMask | KeyReleaseMask |
                          FocusChangeMask;
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
    dxc_a_clipboard = XInternAtom(dxc_display, "CLIPBOARD", False);
    dxc_a_primary = XA_PRIMARY;
    dxc_a_utf8 = XInternAtom(dxc_display, "UTF8_STRING", False);
    dxc_a_targets = XInternAtom(dxc_display, "TARGETS", False);
    dxc_a_text = XInternAtom(dxc_display, "TEXT", False);
    dxc_a_incr = XInternAtom(dxc_display, "INCR", False);
    dxc_a_property = XInternAtom(dxc_display, "DXC_SELECTION", False);
    dxc_a_net_wm_name = XInternAtom(dxc_display, "_NET_WM_NAME", False);
    dxc_a_net_wm_icon = XInternAtom(dxc_display, "_NET_WM_ICON", False);
    dxc_a_net_wm_state = XInternAtom(dxc_display, "_NET_WM_STATE", False);
    dxc_a_maximized_vert = XInternAtom(dxc_display, "_NET_WM_STATE_MAXIMIZED_VERT", False);
    dxc_a_maximized_horz = XInternAtom(dxc_display, "_NET_WM_STATE_MAXIMIZED_HORZ", False);
    dxc_a_moveresize = XInternAtom(dxc_display, "_NET_WM_MOVERESIZE", False);
    dxc_a_active_window = XInternAtom(dxc_display, "_NET_ACTIVE_WINDOW", False);
    dxc_a_motif_hints = XInternAtom(dxc_display, "_MOTIF_WM_HINTS", False);
    dxc_a_xdnd_aware = XInternAtom(dxc_display, "XdndAware", False);
    dxc_a_xdnd_enter = XInternAtom(dxc_display, "XdndEnter", False);
    dxc_a_xdnd_position = XInternAtom(dxc_display, "XdndPosition", False);
    dxc_a_xdnd_status = XInternAtom(dxc_display, "XdndStatus", False);
    dxc_a_xdnd_leave = XInternAtom(dxc_display, "XdndLeave", False);
    dxc_a_xdnd_drop = XInternAtom(dxc_display, "XdndDrop", False);
    dxc_a_xdnd_finished = XInternAtom(dxc_display, "XdndFinished", False);
    dxc_a_xdnd_selection = XInternAtom(dxc_display, "XdndSelection", False);
    dxc_a_xdnd_copy = XInternAtom(dxc_display, "XdndActionCopy", False);
    dxc_a_xdnd_type_list = XInternAtom(dxc_display, "XdndTypeList", False);
    dxc_a_uri_list = XInternAtom(dxc_display, "text/uri-list", False);

    XStoreName(dxc_display, dxc_window, title);
    XChangeProperty(dxc_display, dxc_window, dxc_a_net_wm_name, dxc_a_utf8, 8, PropModeReplace,
                    (const unsigned char *)title, (int)strlen(title));
    // Sizes the window may take, in pixels. A window that cannot be resized is held to the
    // size it opened at; otherwise only the minimum is stated.
    {
        XSizeHints hints;
        memset(&hints, 0, sizeof hints);
        if (dxc_options.min_width > 0 || dxc_options.min_height > 0) {
            hints.flags |= PMinSize;
            hints.min_width = (int)((float)dxc_options.min_width * dxc_scale + 0.5f);
            hints.min_height = (int)((float)dxc_options.min_height * dxc_scale + 0.5f);
        }
        if (!dxc_options.resizable) {
            hints.flags |= PMinSize | PMaxSize;
            hints.min_width = hints.max_width = width;
            hints.min_height = hints.max_height = height;
        }
        if (hints.flags != 0) XSetWMNormalHints(dxc_display, dxc_window, &hints);
    }
    if (!dxc_options.system_chrome) {
        // No frame of the manager's: the renderer draws the caption and the edges. The
        // fields are flags, functions, decorations, input mode and status; only the
        // decorations are set, and to none.
        unsigned long motif[5] = { 2, 0, 0, 0, 0 };
        XChangeProperty(dxc_display, dxc_window, dxc_a_motif_hints, dxc_a_motif_hints, 32,
                        PropModeReplace, (unsigned char *)motif, 5);
    }
    {
        // This window takes files dropped on it, by version 5 of the protocol.
        long version = 5;
        XChangeProperty(dxc_display, dxc_window, dxc_a_xdnd_aware, XA_ATOM, 32, PropModeReplace,
                        (unsigned char *)&version, 1);
    }
    dxc_open_input_method();
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
    *scale = dxc_scale;
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
        fprintf(stderr, "compose-rust: the window holds %d things to say", count);
        if (count > 0) {
            fprintf(stderr, ", the first being \"%s\"", dxc_elements[0].label);
        }
        fprintf(stderr, "\n");
    }
}
