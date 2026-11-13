/*
 * Drops files on a window the way a file manager does, over the X drag-and-drop protocol:
 * XdndEnter, XdndPosition and XdndDrop from a window of its own, with the files served as
 * text/uri-list when the target asks for the selection.
 *
 * Usage: xdnd-drop <window id, decimal or 0x hex> <x> <y> <path>...
 * x and y are in the target window's pixels. Exit status 0 when the target answered
 * XdndFinished having accepted the drop, 1 when it refused or did not answer in time.
 * Built and used by check-linux-input-access.sh; it needs libx11-dev.
 */
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>

static Display *display;
static Window source, target;
static Atom a_selection, a_uri_list, a_finished, a_status;

static void send(Atom type, long l1, long l2, long l3, long l4) {
    XEvent e;
    memset(&e, 0, sizeof e);
    e.xclient.type = ClientMessage;
    e.xclient.window = target;
    e.xclient.message_type = type;
    e.xclient.format = 32;
    e.xclient.data.l[0] = (long)source;
    e.xclient.data.l[1] = l1;
    e.xclient.data.l[2] = l2;
    e.xclient.data.l[3] = l3;
    e.xclient.data.l[4] = l4;
    XSendEvent(display, target, False, NoEventMask, &e);
    XFlush(display);
}

/* Waits up to the given seconds for an event; false on timeout. */
static int next_event(XEvent *e, double seconds) {
    if (XPending(display) == 0) {
        fd_set readable;
        FD_ZERO(&readable);
        FD_SET(ConnectionNumber(display), &readable);
        struct timeval limit = { (time_t)seconds, (suseconds_t)((seconds - (long)seconds) * 1e6) };
        if (select(ConnectionNumber(display) + 1, &readable, NULL, NULL, &limit) <= 0) return 0;
    }
    XNextEvent(display, e);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: %s <window> <x> <y> <path>...\n", argv[0]);
        return 2;
    }
    target = (Window)strtoul(argv[1], NULL, 0);
    int x = atoi(argv[2]), y = atoi(argv[3]);
    char list[16384] = "";
    for (int i = 4; i < argc; i++) {
        strcat(list, "file://");
        for (const char *c = argv[i]; *c; c++) {
            char piece[4];
            if ((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') ||
                *c == '/' || *c == '.' || *c == '-' || *c == '_') {
                piece[0] = *c;
                piece[1] = 0;
            } else {
                snprintf(piece, sizeof piece, "%%%02X", (unsigned char)*c);
            }
            strcat(list, piece);
        }
        strcat(list, "\r\n");
    }
    display = XOpenDisplay(NULL);
    if (display == NULL) {
        fprintf(stderr, "no display\n");
        return 1;
    }
    source = XCreateSimpleWindow(display, DefaultRootWindow(display), 0, 0, 10, 10, 0, 0, 0);
    Atom a_enter = XInternAtom(display, "XdndEnter", False);
    Atom a_position = XInternAtom(display, "XdndPosition", False);
    Atom a_drop = XInternAtom(display, "XdndDrop", False);
    Atom a_copy = XInternAtom(display, "XdndActionCopy", False);
    a_selection = XInternAtom(display, "XdndSelection", False);
    a_uri_list = XInternAtom(display, "text/uri-list", False);
    a_finished = XInternAtom(display, "XdndFinished", False);
    a_status = XInternAtom(display, "XdndStatus", False);
    XSetSelectionOwner(display, a_selection, source, CurrentTime);

    int rx, ry;
    Window child;
    XTranslateCoordinates(display, target, DefaultRootWindow(display), x, y, &rx, &ry, &child);
    send(a_enter, 5L << 24, (long)a_uri_list, 0, 0);
    send(a_position, 0, ((long)rx << 16) | (long)(ry & 0xFFFF), 0, (long)a_copy);

    int accepted = 0, dropped = 0;
    XEvent e;
    for (int waited = 0; waited < 100; waited++) {
        if (!next_event(&e, 0.1)) continue;
        if (e.type == ClientMessage && e.xclient.message_type == a_status && !dropped) {
            accepted = (e.xclient.data.l[1] & 1) != 0;
            if (!accepted) {
                fprintf(stderr, "the window did not accept the drag\n");
                return 1;
            }
            send(a_drop, 0, 0, 0, 0);
            dropped = 1;
        } else if (e.type == SelectionRequest) {
            XSelectionRequestEvent *r = &e.xselectionrequest;
            XChangeProperty(display, r->requestor, r->property, a_uri_list, 8, PropModeReplace,
                            (const unsigned char *)list, (int)strlen(list));
            XEvent reply;
            memset(&reply, 0, sizeof reply);
            reply.xselection.type = SelectionNotify;
            reply.xselection.requestor = r->requestor;
            reply.xselection.selection = r->selection;
            reply.xselection.target = r->target;
            reply.xselection.property = r->property;
            reply.xselection.time = r->time;
            XSendEvent(display, r->requestor, False, NoEventMask, &reply);
            XFlush(display);
        } else if (e.type == ClientMessage && e.xclient.message_type == a_finished) {
            int ok = (e.xclient.data.l[1] & 1) != 0;
            XCloseDisplay(display);
            return ok ? 0 : 1;
        }
    }
    fprintf(stderr, "the window did not answer\n");
    XCloseDisplay(display);
    return 1;
}
