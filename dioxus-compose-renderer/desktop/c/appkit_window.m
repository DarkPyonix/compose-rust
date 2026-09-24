// A window of our own, with a Metal layer in it and no toolkit between.
//
// The renderer draws with Skia into a Metal texture. Everything between that texture and
// the screen is AppKit's, and AppKit is what this file talks to: a window, a view, a
// layer, a device and a queue. What the toolkit was doing here was translating the same
// few things into Java and back, and each translation has been somewhere a frame went
// wrong: a window that insists it is opaque, a peer rebuilt under a surface, a title bar
// belonging to a class we cannot reach.
//
// Nothing here draws. The pixels are Skia's, as they already were.
//
// A layer rather than an MTKView. A view of that kind vends its drawable inside its own
// drawing callback and answers nil outside it, so a renderer that decides for itself when
// a frame happens gets a window that stays black. The layer hands one over whenever it is
// asked, which is the arrangement the frame clock already assumes.

#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

// What happened in the window, waiting to be read.
//
// A queue and not a call. The events arrive on the thread AppKit answers on and the Host
// lives on the one the renderer draws from, so a call would cross between them on every
// click: the boundary is a direct call on one thread and the Host keeps its state there.
// Emptied once per frame instead, which is the same shape the rest of this already has.
enum {
    DXC_EVENT_POINTER_MOVE = 1,
    DXC_EVENT_POINTER_DOWN = 2,
    DXC_EVENT_POINTER_UP = 3,
    DXC_EVENT_SCROLL = 4,
    DXC_EVENT_KEY_DOWN = 5,
    DXC_EVENT_KEY_UP = 6,
    // Text the input method finished, and text it is still working on. The second is
    // what typing Korean, Japanese or Chinese is made of: letters stand in the field,
    // marked, and are replaced as the reader goes rather than piling up.
    DXC_EVENT_TEXT_COMMIT = 7,
    DXC_EVENT_TEXT_COMPOSE = 8,
    // The window is a different size. Carried as an event rather than asked for, because
    // the scene has to be told before the next frame is drawn into a drawable that is
    // already the new size, and asking every frame is the traffic that starved the input
    // method once already.
    DXC_EVENT_RESIZE = 9,
    // Files were dragged over the window, and let go on it. The paths ride in the text
    // field, separated by the one byte no path may contain.
    DXC_EVENT_FILES_ENTERED = 10,
    DXC_EVENT_FILES_DROPPED = 11,
};

// Room for what an input method is composing, which is a syllable or a word and never a
// document. Text longer than this arrives as several commits, which reads the same in a
// field; composition longer than this does not happen.
#define DXC_TEXT_BYTES 96

struct dxc_event {
    int32_t kind;
    // In points from the top left of the content, which is what a scene measures in.
    float x;
    float y;
    int32_t buttons;
    int32_t modifiers;
    // The platform's own key number, and the character it would type. Which Compose key
    // that is gets decided on the other side, where the table lives.
    int32_t key_code;
    int32_t code_point;
    // UTF-8, ending at the first zero. Empty for everything that is not text.
    char text[DXC_TEXT_BYTES];
};

/**
 * Runs a block on the main thread and waits for it.
 *
 * AppKit answers on one thread and the renderer runs on another, which is the arrangement
 * the shell already sets up: the main thread is in `[NSApp run]` and serves its queue.
 * Straight through when already there, because dispatching to the queue you are on and
 * then waiting for it is a deadlock.
 */
static void dxc_on_main(void (^work)(void)) {
    if ([NSThread isMainThread]) {
        work();
    } else {
        dispatch_sync(dispatch_get_main_queue(), work);
    }
}

// What the window would tell a reader who cannot see it.
//
// A snapshot rather than a question. Accessibility is asked for on the thread AppKit
// answers on, at moments nobody chose, and the tree it describes lives where the scene
// does: answering by asking across would block whichever thread asked, and one of them is
// the thread the frame is drawn from. The scene pushes what it has whenever it changes,
// and this answers from that.
struct dxc_element {
    int32_t role;
    // In points from the top left of the view, which is what the scene measures in.
    float x;
    float y;
    float width;
    float height;
    char label[DXC_TEXT_BYTES];
};

// Rebuilt whenever the scene pushes, which is rarely: a tree changes when the screen
// does, not when a frame is drawn.
static NSArray<NSAccessibilityElement *> *dxc_accessibility_children;

// The roles a scene can describe, as numbers, because a name would be a string crossing
// for every element on every push. Which AppKit role each one is is decided here.
enum {
    DXC_ROLE_GROUP = 0,
    DXC_ROLE_BUTTON = 1,
    DXC_ROLE_TEXT = 2,
    DXC_ROLE_FIELD = 3,
    DXC_ROLE_CHECKBOX = 4,
    DXC_ROLE_IMAGE = 5,
};

static NSAccessibilityRole dxc_appkit_role(int32_t role) {
    switch (role) {
        case DXC_ROLE_BUTTON: return NSAccessibilityButtonRole;
        case DXC_ROLE_TEXT: return NSAccessibilityStaticTextRole;
        case DXC_ROLE_FIELD: return NSAccessibilityTextFieldRole;
        case DXC_ROLE_CHECKBOX: return NSAccessibilityCheckBoxRole;
        case DXC_ROLE_IMAGE: return NSAccessibilityImageRole;
        default: return NSAccessibilityGroupRole;
    }
}

// Room for a burst rather than for a session. A queue that fills is a queue nobody is
// draining, and holding a thousand stale mouse moves helps no one.
#define DXC_EVENT_CAPACITY 256

static struct dxc_event dxc_events[DXC_EVENT_CAPACITY];
static int dxc_event_head;
static int dxc_event_count;
static pthread_mutex_t dxc_event_lock = PTHREAD_MUTEX_INITIALIZER;

static void dxc_push_event(struct dxc_event event) {
    pthread_mutex_lock(&dxc_event_lock);
    if (dxc_event_count < DXC_EVENT_CAPACITY) {
        int slot = (dxc_event_head + dxc_event_count) % DXC_EVENT_CAPACITY;
        dxc_events[slot] = event;
        dxc_event_count++;
    } else {
        // Full: the oldest goes. A dropped move from a while ago is a position that has
        // already been overtaken, and dropping the newest would leave the pointer
        // somewhere it no longer is.
        dxc_events[dxc_event_head] = event;
        dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    }
    pthread_mutex_unlock(&dxc_event_lock);
}

/**
 * Replaces what the window tells a reader who cannot see it.
 *
 * Called from the thread the scene lives on, and the elements it builds are read from the
 * thread AppKit asks on, so the array is swapped whole: a reader either sees the tree
 * before this call or the one after it, never half of each.
 */
void dxc_native_set_accessibility(const struct dxc_element *elements, int32_t count, void *view_pointer) {
    // Copied here and built there, without waiting. The frames are in screen coordinates
    // and only the main thread can answer for the window's geometry, but the thread that
    // calls this is the one that drains the window's events: a wait here is a wait on a
    // thread that is already busy answering questions about accessibility, and while it
    // lasts nothing takes the clicks out of the queue. A calculator went deaf this way.
    //
    // Nothing needs the result, so nothing waits for it. The elements are copied first
    // because what was handed in is the caller's stack.
    size_t bytes = (size_t)count * sizeof(struct dxc_element);
    struct dxc_element *copy = count > 0 ? malloc(bytes) : NULL;
    if (copy != NULL) {
        memcpy(copy, elements, bytes);
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        NSView *view = (__bridge NSView *)view_pointer;
        NSMutableArray<NSAccessibilityElement *> *built =
            [NSMutableArray arrayWithCapacity:count];
        for (int32_t index = 0; index < count; index++) {
            const struct dxc_element *element = &copy[index];
            NSString *label = [NSString stringWithUTF8String:element->label];
            NSAccessibilityElement *made = [NSAccessibilityElement
                accessibilityElementWithRole:dxc_appkit_role(element->role)
                                       frame:NSZeroRect
                                       label:label != nil ? label : @""
                                      parent:view];
            NSRect local = NSMakeRect(element->x, element->y, element->width, element->height);
            NSRect inWindow = [view convertRect:local toView:nil];
            NSRect onScreen = [view.window convertRectToScreen:inWindow];
            [made setAccessibilityFrame:onScreen];
            if (index == 0 && getenv("DXC_REPORT_FRAMES") != NULL) {
                fprintf(stderr,
                        "dxc frames: %s scene (%.0f %.0f %.0fx%.0f) -> screen "
                        "(%.0f %.0f %.0fx%.0f)\n",
                        element->label, local.origin.x, local.origin.y,
                        local.size.width, local.size.height,
                        onScreen.origin.x, onScreen.origin.y,
                        onScreen.size.width, onScreen.size.height);
            }
            [built addObject:made];
        }
        dxc_accessibility_children = built;
        free(copy);
    });
}

/**
 * What is on the clipboard, copied into [out], and its length.
 *
 * Text only. A window that pasted a picture into a text field would be worse than one
 * that pasted nothing.
 */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    __block int32_t length = 0;
    dxc_on_main(^{
        @autoreleasepool {
            NSString *text = [NSPasteboard.generalPasteboard stringForType:NSPasteboardTypeString];
            if (text == nil) {
                return;
            }
            const char *utf8 = text.UTF8String;
            if (utf8 == NULL) {
                return;
            }
            strncpy(out, utf8, (size_t)capacity - 1);
            out[capacity - 1] = 0;
            length = (int32_t)strlen(out);
        }
    });
    return length;
}

/** Puts text on the clipboard, replacing what was there. */
void dxc_native_clipboard_write(const char *text) {
    dxc_on_main(^{
        @autoreleasepool {
            NSPasteboard *board = NSPasteboard.generalPasteboard;
            [board clearContents];
            NSString *value = [NSString stringWithUTF8String:text];
            if (value != nil) {
                [board setString:value forType:NSPasteboardTypeString];
            }
        }
    });
}

/**
 * Gives the application the menu every macOS application has.
 *
 * Without one the menu bar shows the application's name and nothing under it, and the
 * shortcuts every reader expects do nothing: command-Q does not quit, command-C does not
 * copy. The items are the system's own actions, so the window is not asked to implement
 * them.
 */
void dxc_native_install_menu(const char *application_name) {
    dxc_on_main(^{
        @autoreleasepool {
            NSString *name = [NSString stringWithUTF8String:application_name];
            if (name == nil) {
                name = @"Application";
            }
            NSMenu *bar = [[NSMenu alloc] init];

            NSMenuItem *appItem = [[NSMenuItem alloc] init];
            NSMenu *appMenu = [[NSMenu alloc] init];
            [appMenu addItemWithTitle:[@"Hide " stringByAppendingString:name]
                               action:@selector(hide:)
                        keyEquivalent:@"h"];
            [appMenu addItem:NSMenuItem.separatorItem];
            [appMenu addItemWithTitle:[@"Quit " stringByAppendingString:name]
                               action:@selector(terminate:)
                        keyEquivalent:@"q"];
            appItem.submenu = appMenu;
            [bar addItem:appItem];

            // Cut, copy, paste and select all are the system's actions, sent to whatever
            // holds focus. The window answers them through the text input it already has.
            NSMenuItem *editItem = [[NSMenuItem alloc] init];
            NSMenu *editMenu = [[NSMenu alloc] initWithTitle:@"Edit"];
            [editMenu addItemWithTitle:@"Undo" action:@selector(undo:) keyEquivalent:@"z"];
            [editMenu addItem:NSMenuItem.separatorItem];
            [editMenu addItemWithTitle:@"Cut" action:@selector(cut:) keyEquivalent:@"x"];
            [editMenu addItemWithTitle:@"Copy" action:@selector(copy:) keyEquivalent:@"c"];
            [editMenu addItemWithTitle:@"Paste" action:@selector(paste:) keyEquivalent:@"v"];
            [editMenu addItemWithTitle:@"Select All"
                                action:@selector(selectAll:)
                         keyEquivalent:@"a"];
            editItem.submenu = editMenu;
            [bar addItem:editItem];

            NSApp.mainMenu = bar;
        }
    });
}

/**
 * Lets the window answer for itself for a moment.
 *
 * The thread that draws is the thread AppKit delivers on, so a frame that never gave the
 * run loop a turn would be a window that never heard a click. This is that turn: events
 * arrive, timers fire, and the queue above fills, all before the next frame is drawn.
 *
 * Returns straight away when there is nothing waiting, so a window with nothing happening
 * in it costs a call rather than the whole of [seconds].
 */
void dxc_native_pump(double seconds) {
    @autoreleasepool {
        NSDate *until = [NSDate dateWithTimeIntervalSinceNow:seconds];
        for (;;) {
            NSEvent *event = [NSApp nextEventMatchingMask:NSEventMaskAny
                                                untilDate:until
                                                   inMode:NSDefaultRunLoopMode
                                                  dequeue:YES];
            if (event == nil) {
                break;
            }
            [NSApp sendEvent:event];
            // Whatever else is waiting is taken without waiting again: one turn of the
            // loop should empty what has arrived, not sleep once per event.
            until = NSDate.distantPast;
        }
    }
}

/** Takes the oldest event, or answers zero when there is none. */
int32_t dxc_native_poll_event(struct dxc_event *out) {
    int32_t taken = 0;
    pthread_mutex_lock(&dxc_event_lock);
    if (dxc_event_count > 0) {
        *out = dxc_events[dxc_event_head];
        dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
        dxc_event_count--;
        taken = 1;
    }
    pthread_mutex_unlock(&dxc_event_lock);
    return taken;
}

// What the input method is currently composing, and nil when nothing is.
//
// Kept because the input method asks for it back. Building a syllable out of letters
// means reading what is already there and replacing it, so a client that answers nothing
// is a client whose last letter has vanished: the method gives up on combining and
// commits each letter on its own. That is exactly what this looked like before the
// answer existed, with every consonant and vowel standing separately.
static NSString *dxc_marked_text;

/**
 * The view the window is filled with.
 *
 * It exists to receive. AppKit sends mouse and key events to the view under the pointer
 * and to the one holding focus, and a plain NSView answers none of them; everything here
 * turns one into a record and puts it on the queue above.
 */
@interface DxcView : NSView <NSTextInputClient>
@end

/** True once the window has been closed, so the renderer knows to stop. */
static atomic_bool dxc_window_closed;

int32_t dxc_native_window_closed(void) {
    return atomic_load(&dxc_window_closed) ? 1 : 0;
}

// Hears the close button, which is the system's and not ours to draw or to wire up.
@interface DxcWindowDelegate : NSObject <NSWindowDelegate>
@end

@implementation DxcWindowDelegate
- (void)windowWillClose:(NSNotification *)notification {
    atomic_store(&dxc_window_closed, true);
}
@end

// The shape of a pointer, as a number both sides agree on. A name would be a string
// crossing every time the pointer moved over a different control.
enum {
    DXC_CURSOR_ARROW = 0,
    DXC_CURSOR_HAND = 1,
    DXC_CURSOR_TEXT = 2,
    DXC_CURSOR_CROSSHAIR = 3,
    DXC_CURSOR_RESIZE_LEFT_RIGHT = 4,
    DXC_CURSOR_RESIZE_UP_DOWN = 5,
};

/**
 * Sets the shape of the pointer over this window.
 *
 * Called from the thread the scene runs on, because that is where a control decides what
 * the pointer should look like over it, and done on the main thread because the cursor
 * belongs to the window.
 */
void dxc_native_set_cursor(int32_t shape) {
    dxc_on_main(^{
        NSCursor *cursor = nil;
        switch (shape) {
            case DXC_CURSOR_HAND: cursor = NSCursor.pointingHandCursor; break;
            case DXC_CURSOR_TEXT: cursor = NSCursor.IBeamCursor; break;
            case DXC_CURSOR_CROSSHAIR: cursor = NSCursor.crosshairCursor; break;
            case DXC_CURSOR_RESIZE_LEFT_RIGHT: cursor = NSCursor.resizeLeftRightCursor; break;
            case DXC_CURSOR_RESIZE_UP_DOWN: cursor = NSCursor.resizeUpDownCursor; break;
            default: cursor = NSCursor.arrowCursor; break;
        }
        [cursor set];
    });
}

@implementation DxcView

- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)isFlipped { return YES; }

// The click that brings a window forward is delivered as well as being spent on the
// bringing. Without this the first press on a control nobody has focused yet is eaten by
// the activation, which reads to the reader as a control that ignored them once.
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }

- (void)dxcSend:(int32_t)kind event:(NSEvent *)event {
    NSPoint where = [self convertPoint:event.locationInWindow fromView:nil];
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = (float)where.x;
    record.y = (float)where.y;
    record.buttons = (int32_t)NSEvent.pressedMouseButtons;
    record.modifiers = (int32_t)event.modifierFlags;
    if (kind == DXC_EVENT_SCROLL) {
        // The wheel's travel rides in the same two fields the pointer uses, because a
        // scroll has no position of its own beyond where the pointer already is.
        record.x = (float)event.scrollingDeltaX;
        record.y = (float)event.scrollingDeltaY;
    }
    if (kind == DXC_EVENT_KEY_DOWN || kind == DXC_EVENT_KEY_UP) {
        record.key_code = (int32_t)event.keyCode;
        NSString *typed = event.charactersIgnoringModifiers;
        record.code_point = typed.length > 0 ? (int32_t)[typed characterAtIndex:0] : 0;
    }
    dxc_push_event(record);
}

- (void)mouseMoved:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_MOVE event:event]; }
- (void)mouseDragged:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_MOVE event:event]; }
- (void)mouseDown:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_DOWN event:event]; }
- (void)mouseUp:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_UP event:event]; }
- (void)rightMouseDown:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_DOWN event:event]; }
- (void)rightMouseUp:(NSEvent *)event { [self dxcSend:DXC_EVENT_POINTER_UP event:event]; }
- (void)scrollWheel:(NSEvent *)event { [self dxcSend:DXC_EVENT_SCROLL event:event]; }
// Both, and in this order. The key itself is what arrows, Enter and backspace are read
// as, and `interpretKeyEvents:` is what turns the rest into text: it hands the event to
// the input context, which answers with `insertText:` for a letter and with
// `setMarkedText:` while a syllable is still being built. A path that only queued the key
// would type English and lose every language that composes.
- (void)keyDown:(NSEvent *)event {
    [self dxcSend:DXC_EVENT_KEY_DOWN event:event];
    // Handed to the input context rather than interpreted. Interpreting also turns keys
    // into editing commands for a text system this window does not have, and the keys
    // have already gone to the scene, which has its own.
    [self.inputContext handleEvent:event];
}
- (void)keyUp:(NSEvent *)event { [self dxcSend:DXC_EVENT_KEY_UP event:event]; }

#pragma mark - Files dragged onto the window

// What a drag is carrying, written into an event the same way text is.
//
// Only files. A drag of anything else is refused rather than delivered as an empty list,
// because a window that accepts a drag and then does nothing with it is worse than one
// that never offered.
- (void)dxcSendPaths:(int32_t)kind info:(id<NSDraggingInfo>)info {
    NSArray<NSURL *> *urls = [info.draggingPasteboard
        readObjectsForClasses:@[NSURL.class]
                      options:@{NSPasteboardURLReadingFileURLsOnlyKey: @YES}];
    NSMutableArray<NSString *> *paths = [NSMutableArray arrayWithCapacity:urls.count];
    for (NSURL *url in urls) {
        if (url.path != nil) {
            [paths addObject:url.path];
        }
    }
    // NUL, because it is the one byte no path on any desktop may contain.
    [self dxcSendText:kind string:[paths componentsJoinedByString:@"\0"]];
}

- (NSDragOperation)draggingEntered:(id<NSDraggingInfo>)sender {
    [self dxcSendPaths:DXC_EVENT_FILES_ENTERED info:sender];
    return NSDragOperationCopy;
}

- (BOOL)performDragOperation:(id<NSDraggingInfo>)sender {
    [self dxcSendPaths:DXC_EVENT_FILES_DROPPED info:sender];
    return YES;
}

#pragma mark - NSAccessibility

// The window's contents, as elements rather than as pixels.
//
// A reader who cannot see the window gets this and nothing else, so an empty answer is a
// window that appears to contain nothing at all. What is in it is whatever the scene last
// pushed.
- (NSArray *)accessibilityChildren { return dxc_accessibility_children ?: @[]; }
- (NSArray *)accessibilityChildrenInNavigationOrder { return self.accessibilityChildren; }
- (NSAccessibilityRole)accessibilityRole { return NSAccessibilityGroupRole; }
- (BOOL)isAccessibilityElement { return YES; }

#pragma mark - NSTextInputClient

// What the input method is building, if anything. Held as a range over the text it gave
// us rather than over the field's contents, because the field is on the other side of the
// boundary and answering for it would mean asking across on every keystroke.
- (BOOL)hasMarkedText { return dxc_marked_text.length > 0; }
- (NSRange)markedRange {
    return dxc_marked_text.length > 0 ?
        NSMakeRange(0, dxc_marked_text.length) : NSMakeRange(NSNotFound, 0);
}
// Where the caret is, and "nowhere this client can say" when nothing is being composed.
//
// Said as not-found rather than as zero. Zero is a real place, and an input method told
// the caret is at the start of a document it cannot read decides the client is not one it
// can compose into: it stops marking and commits every letter on its own, which is what
// this looked like with a zero here.
- (NSRange)selectedRange {
    return dxc_marked_text.length > 0 ?
        NSMakeRange(dxc_marked_text.length, 0) : NSMakeRange(NSNotFound, 0);
}
- (NSArray<NSAttributedStringKey> *)validAttributesForMarkedText { return @[]; }

- (void)dxcSendText:(int32_t)kind string:(NSString *)text {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    const char *utf8 = text.UTF8String;
    if (utf8 != NULL) {
        strncpy(record.text, utf8, DXC_TEXT_BYTES - 1);
    }
    dxc_push_event(record);
}

- (void)insertText:(id)string replacementRange:(NSRange)replacementRange {
    NSString *text = [string isKindOfClass:NSAttributedString.class] ?
        ((NSAttributedString *)string).string : (NSString *)string;
    dxc_marked_text = nil;
    [self dxcSendText:DXC_EVENT_TEXT_COMMIT string:text];
}

- (void)setMarkedText:(id)string
        selectedRange:(NSRange)selectedRange
     replacementRange:(NSRange)replacementRange {
    NSString *text = [string isKindOfClass:NSAttributedString.class] ?
        ((NSAttributedString *)string).string : (NSString *)string;
    dxc_marked_text = text.length > 0 ? text : nil;
    [self dxcSendText:DXC_EVENT_TEXT_COMPOSE string:text];
}

// The reader backed out of what was being composed. An empty composition ends it without
// putting anything in the field.
- (void)unmarkText {
    dxc_marked_text = nil;
    [self dxcSendText:DXC_EVENT_TEXT_COMPOSE string:@""];
}

// What is being composed, when asked for it back.
//
// Only the marked text. The field's own contents are on the other side of the boundary
// and answering for them would mean asking across on every keystroke, which is the cost
// the whole arrangement is avoiding. An input method that wants more than it is composing
// is asking about text it did not write.
- (NSAttributedString *)attributedSubstringForProposedRange:(NSRange)range
                                                actualRange:(NSRangePointer)actualRange {
    if (dxc_marked_text == nil) {
        return nil;
    }
    NSRange available = NSMakeRange(0, dxc_marked_text.length);
    NSRange wanted = NSIntersectionRange(range, available);
    if (wanted.length == 0) {
        return nil;
    }
    if (actualRange != NULL) {
        *actualRange = wanted;
    }
    return [[NSAttributedString alloc] initWithString:[dxc_marked_text substringWithRange:wanted]];
}

- (NSUInteger)characterIndexForPoint:(NSPoint)point { return NSNotFound; }

// Where the candidate list is put. The caret's own place is on the other side of the
// boundary; until it is asked for, the top left of the view keeps the list on screen and
// near enough to read, which is better than the bottom of the display.
- (NSRect)firstRectForCharacterRange:(NSRange)range actualRange:(NSRangePointer)actualRange {
    NSRect local = NSMakeRect(0, 0, 1, 20);
    NSRect windowRect = [self convertRect:local toView:nil];
    return [self.window convertRectToScreen:windowRect];
}

// Keys that mean an action rather than a letter. They were queued as keys already and the
// field reads them there, so nothing more is done with them here. Answering at all is
// what stops AppKit from sounding the alert for every arrow key.
- (void)doCommandBySelector:(SEL)selector { }

// Without a tracking area the view hears a moving pointer only while a button is held,
// and hover is half of what a desktop control does.
// The window changed size. The layer is told first, because a drawable handed out at the
// old size would be drawn into at the new one, and the scene is told through the queue so
// that it changes its mind between frames rather than during one.
- (void)setFrameSize:(NSSize)size {
    [super setFrameSize:size];
    CAMetalLayer *layer = (CAMetalLayer *)self.layer;
    CGFloat scale = self.window.backingScaleFactor > 0 ? self.window.backingScaleFactor : 1;
    layer.contentsScale = scale;
    layer.drawableSize = CGSizeMake(size.width * scale, size.height * scale);

    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_RESIZE;
    record.x = (float)(size.width * scale);
    record.y = (float)(size.height * scale);
    dxc_push_event(record);
}

- (void)updateTrackingAreas {
    for (NSTrackingArea *area in self.trackingAreas) {
        [self removeTrackingArea:area];
    }
    NSTrackingAreaOptions options = NSTrackingMouseMoved | NSTrackingActiveInKeyWindow |
        NSTrackingInVisibleRect;
    [self addTrackingArea:[[NSTrackingArea alloc] initWithRect:self.bounds
                                                      options:options
                                                        owner:self
                                                     userInfo:nil]];
    [super updateTrackingAreas];
}

@end

struct dxc_native_window {
    void *window;
    void *view;
    void *device;
    void *queue;
    void *layer;
};

/**
 * Opens a window with a Metal layer filling it.
 *
 * Returns zero on success; a non-zero answer means the machine has no Metal device, which
 * is the one failure here that is not a mistake of ours.
 */
int32_t dxc_native_window_open(
    const char *title,
    int32_t width,
    int32_t height,
    struct dxc_native_window *out
) {
    __block int32_t status = 0;
    dxc_on_main(^{
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            status = 1;
            return;
        }
        id<MTLCommandQueue> queue = [device newCommandQueue];
        if (queue == nil) {
            status = 2;
            return;
        }

        NSRect frame = NSMakeRect(0, 0, width, height);
        NSWindowStyleMask mask = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
            NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable |
            NSWindowStyleMaskFullSizeContentView;
        NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                      styleMask:mask
                                                        backing:NSBackingStoreBuffered
                                                          defer:NO];
        window.title = [NSString stringWithUTF8String:title];
        window.titlebarAppearsTransparent = YES;
        window.titleVisibility = NSWindowTitleHidden;
        window.releasedWhenClosed = NO;
        // Held for the life of the window, which owns it through the delegate reference.
        static DxcWindowDelegate *delegate;
        delegate = [[DxcWindowDelegate alloc] init];
        window.delegate = delegate;

        DxcView *view = [[DxcView alloc] initWithFrame:frame];
        CAMetalLayer *layer = [CAMetalLayer layer];
        layer.device = device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        // Skia reads the texture back in places, and a framebuffer-only one cannot be.
        layer.framebufferOnly = NO;
        // Drawn at the density of the screen the window is on rather than in points, so
        // text is as sharp as the display can draw it.
        layer.contentsScale = window.backingScaleFactor;
        layer.drawableSize = CGSizeMake(width * layer.contentsScale,
                                        height * layer.contentsScale);
        view.wantsLayer = YES;
        view.layer = layer;

        window.contentView = view;
        [window makeFirstResponder:view];
        [view registerForDraggedTypes:@[NSPasteboardTypeFileURL]];
        window.acceptsMouseMovedEvents = YES;
        [window center];
        [window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];

        // Held past this scope. The window owns the view, and the renderer owns the
        // window until it closes it.
        out->window = (__bridge_retained void *)window;
        out->view = (__bridge_retained void *)view;
        out->device = (__bridge_retained void *)device;
        out->queue = (__bridge_retained void *)queue;
        out->layer = (__bridge_retained void *)layer;
    }
    });
    return status;
}

/** What the layer is drawn at, in pixels, and how many of them go to a point. */
void dxc_native_window_size(void *layer_pointer, int32_t *width, int32_t *height, float *scale) {
    dxc_on_main(^{
    @autoreleasepool {
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layer_pointer;
        CGSize size = layer.drawableSize;
        *width = (int32_t)size.width;
        *height = (int32_t)size.height;
        *scale = (float)layer.contentsScale;
    }
    });
}

// The drawable the frame being painted belongs to.
//
// Held here between beginning a frame and ending it, because the renderer takes a texture
// and gives back pixels, and the drawable the texture came from is what has to be handed
// to the screen afterwards. One window's worth: a second window would make this a field
// of the window rather than a file-level one.
static id<CAMetalDrawable> dxc_pending_drawable;

/**
 * Takes the next drawable and answers the texture to paint into.
 *
 * Zero when the system has none to give, which happens when frames are being produced
 * faster than the screen takes them. That is not an error: the frame is skipped and the
 * next one asks again.
 */
int32_t dxc_native_frame_begin(void *layer_pointer, void **texture_out) {
    __block int32_t status = 0;
    dxc_on_main(^{
    @autoreleasepool {
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layer_pointer;
        id<CAMetalDrawable> drawable = [layer nextDrawable];
        if (drawable == nil) {
            status = 1;
            return;
        }
        dxc_pending_drawable = drawable;
        *texture_out = (__bridge void *)drawable.texture;
    }
    });
    return status;
}

/** Puts the painted drawable on the screen and lets it go. */
void dxc_native_frame_end(void *queue_pointer) {
    dxc_on_main(^{
    @autoreleasepool {
        if (dxc_pending_drawable == nil) {
            return;
        }
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)queue_pointer;
        id<MTLCommandBuffer> buffer = [queue commandBuffer];
        [buffer presentDrawable:dxc_pending_drawable];
        [buffer commit];
        dxc_pending_drawable = nil;
    }
    });
}
