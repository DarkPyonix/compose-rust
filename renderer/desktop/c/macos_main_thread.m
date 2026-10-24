/*
 * macOS main-thread handling for the renderer.
 *
 * AppKit must run on the main thread, and the renderer runs on it: the window is its own,
 * drawn and driven from the thread AppKit answers on. The application object is created
 * here, before the renderer starts, so nothing finds it missing.
 */
#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#include <mach/mach.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Called on the main thread before the renderer thread starts, so nothing finds NSApp missing
   and the renderer, which runs on this thread, finds it ready. */
void compose_rust_prepare_main_thread(void) {
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
}

int32_t dxc_appkit_window_focused(void *window_pointer) {
    if (window_pointer == NULL) {
        return 0;
    }
    NSWindow *window = (__bridge NSWindow *)window_pointer;
    return (NSApp.isActive && window.isKeyWindow) ? 1 : 0;
}

static long dxc_idle_mb(long long bytes) {
    return bytes < 0 ? -1 : (long)((bytes + 524288) / 1048576);
}

int32_t dxc_appkit_process_footprint_mb(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS) {
        return -1;
    }
    return (int32_t)dxc_idle_mb((long long)info.phys_footprint);
}

int32_t dxc_appkit_process_resident_mb(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS) {
        return -1;
    }
    return (int32_t)dxc_idle_mb((long long)info.resident_size);
}

int32_t dxc_appkit_metal_allocated_mb(void *device_pointer) {
    if (device_pointer == NULL) {
        return -1;
    }
    id<MTLDevice> device = (__bridge id<MTLDevice>)device_pointer;
    return (int32_t)dxc_idle_mb((long long)device.currentAllocatedSize);
}

void dxc_appkit_footprint_breakdown(int32_t elapsed_s, int32_t is_end) {
    const char *wanted = getenv("DXC_METRICS_BREAKDOWN");
    if (wanted == NULL || strcmp(wanted, "1") != 0) {
        return;
    }
    const char *phase = is_end ? "end" : (elapsed_s == 0 ? "start" : "sample");
    char command[256];
    snprintf(command, sizeof command,
             "/usr/bin/footprint %d 2>/dev/null | sed 's/^/compose-rust: idle-soak breakdown elapsed_s=%d %s /' 1>&2",
             (int)getpid(), (int)elapsed_s, phase);
    fflush(stderr);
    system(command);
}
