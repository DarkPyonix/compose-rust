/*
 * Notifications for the macOS native image, through UNUserNotificationCenter.
 *
 * The Kotlin/Native renderer reaches the same framework through its platform library. The
 * native image has no such library, so the handful of calls it needs are here, behind the
 * dxc_notify_* names every desktop answers (Windows in win32_notifications.c, Linux with
 * stubs in x11_window.c, because Linux speaks D-Bus from Kotlin instead).
 *
 * Every call comes from the renderer's UI thread. What the notification centre answers, a
 * permission or a press, arrives on a queue of its own: it is put on a list here and the
 * renderer is asked for a frame, and the frame takes it off the list on the UI thread. That
 * request is the same thread-safe one a Host worker makes, so there is no second way in.
 *
 * Compiled with ARC, like the window beside it.
 */
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

void compose_rust_renderer_request_frame(void);

/* What the renderer reads back. The numbers are the wire's own permission tags. */
enum {
    DXC_NOTIFY_NONE = 0,
    DXC_NOTIFY_ACTIVATED = 1,
    DXC_NOTIFY_PERMISSION = 2,
};
enum {
    DXC_PERMISSION_FINDING_OUT = 0,
    DXC_PERMISSION_NOT_DETERMINED = 1,
    DXC_PERMISSION_GRANTED = 2,
    DXC_PERMISSION_DENIED = 3,
    DXC_PERMISSION_UNSUPPORTED = 4,
};

static NSString *const DxcAction1 = @"compose-rust.action.1";
static NSString *const DxcAction2 = @"compose-rust.action.2";

typedef struct dxc_notify_event {
    int32_t kind;
    int32_t value;
    char *key;
    struct dxc_notify_event *next;
} dxc_notify_event;

static pthread_mutex_t dxc_notify_lock = PTHREAD_MUTEX_INITIALIZER;
static dxc_notify_event *dxc_notify_head;
static dxc_notify_event *dxc_notify_tail;

/* Queues one answer and asks for the frame that delivers it. Any thread. */
static void dxc_notify_push(int32_t kind, int32_t value, NSString *key) {
    dxc_notify_event *event = calloc(1, sizeof(dxc_notify_event));
    if (event == NULL) return;
    event->kind = kind;
    event->value = value;
    event->key = key == nil ? NULL : strdup(key.UTF8String);
    pthread_mutex_lock(&dxc_notify_lock);
    if (dxc_notify_tail == NULL) {
        dxc_notify_head = event;
    } else {
        dxc_notify_tail->next = event;
    }
    dxc_notify_tail = event;
    pthread_mutex_unlock(&dxc_notify_lock);
    compose_rust_renderer_request_frame();
}

static int32_t dxc_permission_of(UNAuthorizationStatus status) {
    switch (status) {
    case UNAuthorizationStatusNotDetermined:
        return DXC_PERMISSION_NOT_DETERMINED;
    case UNAuthorizationStatusDenied:
        return DXC_PERMISSION_DENIED;
    default:
        /* Authorized, provisional and ephemeral all show what is posted. */
        return DXC_PERMISSION_GRANTED;
    }
}

@interface DxcNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation DxcNotificationDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
    NSString *identifier = response.actionIdentifier;
    int32_t action = -1;
    if ([identifier isEqualToString:UNNotificationDefaultActionIdentifier]) {
        action = 0;
    } else if ([identifier isEqualToString:DxcAction1]) {
        action = 1;
    } else if ([identifier isEqualToString:DxcAction2]) {
        action = 2;
    }
    /* Dismissing a notification is not pressing it. */
    if (action >= 0) {
        if (action == 0) {
            /* The body brings the application up and its window back from the Dock. A
             * button does not: it is there to be dealt with without opening anything. */
            dispatch_async(dispatch_get_main_queue(), ^{
                [NSApp activateIgnoringOtherApps:YES];
                for (NSWindow *window in NSApp.windows) {
                    if (window.miniaturized) [window deminiaturize:nil];
                }
            });
        }
        dxc_notify_push(DXC_NOTIFY_ACTIVATED, action, response.notification.request.identifier);
    }
    completionHandler();
}

/* Shown even while the application is in front. Whether one should be was decided before
 * it was posted: a notification that asked not to be shown then was never posted. */
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
                      UNNotificationPresentationOptionSound);
}

@end

static UNUserNotificationCenter *dxc_center;
/* The centre holds its delegate weakly, so this is what keeps it alive. */
static DxcNotificationDelegate *dxc_delegate;
static NSMutableDictionary<NSString *, UNNotificationCategory *> *dxc_categories;

static NSString *dxc_string(const char *text) {
    return text == NULL ? @"" : [NSString stringWithUTF8String:text] ?: @"";
}

void dxc_notify_refresh_permission(void) {
    if (dxc_center == nil) return;
    [dxc_center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        dxc_notify_push(DXC_NOTIFY_PERMISSION, dxc_permission_of(settings.authorizationStatus), nil);
    }];
}

int32_t dxc_notify_start(void) {
    /* A process with no bundle identifier is refused by the centre, and refused with an
     * exception rather than a nil, so it is not asked. A bare executable run from a build
     * directory is that process; a signed application bundle is not. */
    if (NSBundle.mainBundle.bundleIdentifier == nil) return DXC_PERMISSION_UNSUPPORTED;
    if (dxc_center == nil) {
        dxc_center = [UNUserNotificationCenter currentNotificationCenter];
        dxc_delegate = [DxcNotificationDelegate new];
        dxc_center.delegate = dxc_delegate;
        dxc_categories = [NSMutableDictionary new];
    }
    dxc_notify_refresh_permission();
    return DXC_PERMISSION_FINDING_OUT;
}

void dxc_notify_request_permission(void) {
    if (dxc_center == nil) return;
    UNAuthorizationOptions options =
        UNAuthorizationOptionAlert | UNAuthorizationOptionSound | UNAuthorizationOptionBadge;
    [dxc_center requestAuthorizationWithOptions:options
                              completionHandler:^(BOOL granted, NSError *error) {
                                  dxc_notify_push(DXC_NOTIFY_PERMISSION,
                                                  granted ? DXC_PERMISSION_GRANTED
                                                          : DXC_PERMISSION_DENIED,
                                                  nil);
                              }];
}

/* The category carrying one pair of button labels, registered the first time it is seen. */
static NSString *dxc_category(NSString *first, NSString *second) {
    if (first.length == 0 && second.length == 0) return nil;
    NSString *name = [NSString stringWithFormat:@"compose-rust/%@/%@", first, second];
    if (dxc_categories[name] == nil) {
        NSMutableArray<UNNotificationAction *> *actions = [NSMutableArray new];
        if (first.length > 0) {
            [actions addObject:[UNNotificationAction actionWithIdentifier:DxcAction1
                                                                    title:first
                                                                  options:UNNotificationActionOptionNone]];
        }
        if (second.length > 0) {
            [actions addObject:[UNNotificationAction actionWithIdentifier:DxcAction2
                                                                    title:second
                                                                  options:UNNotificationActionOptionNone]];
        }
        dxc_categories[name] = [UNNotificationCategory categoryWithIdentifier:name
                                                                      actions:actions
                                                            intentIdentifiers:@[]
                                                                      options:UNNotificationCategoryOptionNone];
        /* The whole set each time: registering replaces what was registered before. */
        [dxc_center setNotificationCategories:[NSSet setWithArray:dxc_categories.allValues]];
    }
    return name;
}

void dxc_notify_post(const char *key, const char *title, const char *body, const char *channel,
                     const char *action1, const char *action2, int32_t urgent) {
    if (dxc_center == nil) return;
    UNMutableNotificationContent *content = [UNMutableNotificationContent new];
    content.title = dxc_string(title);
    content.body = dxc_string(body);
    content.sound = [UNNotificationSound defaultSound];
    NSString *thread = dxc_string(channel);
    /* A thread is the nearest this platform has to a channel: the notification centre
     * stacks one together, and what a user can turn off stays per application. */
    if (thread.length > 0) content.threadIdentifier = thread;
    NSString *category = dxc_category(dxc_string(action1), dxc_string(action2));
    if (category != nil) content.categoryIdentifier = category;
    if (urgent) {
        if (@available(macOS 12.0, *)) {
            content.interruptionLevel = UNNotificationInterruptionLevelTimeSensitive;
        }
    }
    /* The key is the request's identifier, so a second post under it replaces the first. */
    UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:dxc_string(key)
                                                                          content:content
                                                                          trigger:nil];
    [dxc_center addNotificationRequest:request withCompletionHandler:nil];
}

void dxc_notify_withdraw(const char *key) {
    if (dxc_center == nil) return;
    NSArray<NSString *> *identifiers = @[ dxc_string(key) ];
    [dxc_center removePendingNotificationRequestsWithIdentifiers:identifiers];
    [dxc_center removeDeliveredNotificationsWithIdentifiers:identifiers];
}

/* The process is ending: a notification left behind would point at work no process knows
 * about any more, and pressing it would start the application for nothing. */
void dxc_notify_withdraw_all(void) {
    if (dxc_center == nil) return;
    [dxc_center removeAllPendingNotificationRequests];
    [dxc_center removeAllDeliveredNotifications];
}

/*
 * Takes the oldest answer off the list. Returns its kind, or none when the list is empty.
 * The key of a press is copied into `key` and terminated, cut short at `capacity - 1`
 * bytes; the renderer asks with room for any key it gives out.
 */
int32_t dxc_notify_next_event(char *key, int32_t capacity, int32_t *value) {
    pthread_mutex_lock(&dxc_notify_lock);
    dxc_notify_event *event = dxc_notify_head;
    if (event != NULL) {
        dxc_notify_head = event->next;
        if (dxc_notify_head == NULL) dxc_notify_tail = NULL;
    }
    pthread_mutex_unlock(&dxc_notify_lock);
    if (event == NULL) return DXC_NOTIFY_NONE;
    int32_t kind = event->kind;
    *value = event->value;
    if (capacity > 0) {
        size_t length = event->key == NULL ? 0 : strlen(event->key);
        if (length > (size_t)(capacity - 1)) length = (size_t)(capacity - 1);
        if (length > 0) memcpy(key, event->key, length);
        key[length] = '\0';
    }
    free(event->key);
    free(event);
    return kind;
}
