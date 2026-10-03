// Traces the Objective-C half of the AWT accessibility bridge.
//
// The desktop renderer must expose the same accessibility tree from a native image as it
// does on the JVM. It did not: the native build published a single element and aborted
// when the tree was queried, because the linker dropped the role classes AppKit looks up
// by name. This probe is how that was traced.
//
// The native image aborts with `NSInvalidArgumentException: object cannot be nil` while
// AppKit builds a window's children, which means `+[CommonComponentAccessibility
// createWithParent:withClass:...]` handed back nil. That method returns nil on two
// distinct branches, and the abort alone does not say which:
//
//   1. `getCAccessible:withEnv:` returned NULL, so the Java side never produced a
//      `CAccessible` for the child.
//   2. `getComponentAccessibilityClass:` returned a nil Class, so `[classType alloc]`
//      was a message to nil.
//
// Telling those apart with a debugger needs a debug image, because the shipped library is
// stripped and lldb cannot resolve a selector in it. Swizzling does not: the Objective-C
// runtime still carries the class and its selectors, so this library wraps the three
// methods and logs their arguments and results.
//
// Build and run:
//
//     clang -dynamiclib -framework Foundation -framework AppKit \
//         -o /tmp/ax-probe.dylib experiments/accessibility/ax-probe.m
//     DYLD_INSERT_LIBRARIES=/tmp/ax-probe.dylib ./desktop/scripts/smoke-test.sh
//
// then attach ax-dump.swift to the process. The log lines are tagged `[axprobe]`.
//
// The class lives in the renderer library, which is loaded long after this one, so a
// background thread waits for it rather than swizzling at load time. Accessibility is
// only ever touched once a client attaches, which is much later still.

#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#import <objc/message.h>

typedef NSArray *(*children_fn)(id, SEL, id, void *, NSInteger, BOOL, BOOL);
typedef void (*rolesmap_fn)(id, SEL);
typedef id (*create_fn)(id, SEL, id, Class, void *, NSString *, int, void *, id);
typedef void *(*getcax_fn)(id, SEL, void *, void *);
typedef Class (*roleclass_fn)(id, SEL, NSString *, id);

static create_fn sOriginalCreate;
static getcax_fn sOriginalGetCAccessible;
static roleclass_fn sOriginalRoleClass;
static children_fn sOriginalChildren;
static rolesmap_fn sOriginalRolesMap;

static NSArray *probe_children(id self, SEL _cmd, id parent, void *env, NSInteger which,
                               BOOL allowIgnored, BOOL recursive) {
    NSLog(@"[axprobe] childrenOfParent enter which=%ld allowIgnored=%d", (long)which, allowIgnored);
    return sOriginalChildren(self, _cmd, parent, env, which, allowIgnored, recursive);
}

static void probe_rolesMap(id self, SEL _cmd) {
    NSLog(@"[axprobe] initializeRolesMap enter (this is when CAccessibility.getAccessibility runs)");
    sOriginalRolesMap(self, _cmd);
    NSLog(@"[axprobe] initializeRolesMap done");
}

static id probe_create(id self, SEL _cmd, id parent, Class classType, void *jaccessible,
                       NSString *role, int index, void *env, id view) {
    id result = sOriginalCreate(self, _cmd, parent, classType, jaccessible, role, index, env, view);
    NSLog(@"[axprobe] createWithParent role=%@ classType=%@ accessible=%p -> %@",
          role, classType == nil ? @"(nil)" : NSStringFromClass(classType), jaccessible,
          result == nil ? @"(nil)" : [result description]);
    return result;
}

static void *probe_getCAccessible(id self, SEL _cmd, void *jaccessible, void *env) {
    void *result = sOriginalGetCAccessible(self, _cmd, jaccessible, env);
    NSLog(@"[axprobe] getCAccessible accessible=%p -> %p", jaccessible, result);
    return result;
}

static Class probe_roleClass(id self, SEL _cmd, NSString *role, id parent) {
    Class result = sOriginalRoleClass(self, _cmd, role, parent);
    NSLog(@"[axprobe] getComponentAccessibilityClass role=%@ -> %@",
          role, result == nil ? @"(nil)" : NSStringFromClass(result));
    return result;
}

static void swizzleClassMethod(Class cls, SEL selector, IMP replacement, void *original) {
    Method method = class_getClassMethod(cls, selector);
    if (method == NULL) {
        NSLog(@"[axprobe] no class method %@ on %@", NSStringFromSelector(selector), cls);
        return;
    }
    *(IMP *)original = method_setImplementation(method, replacement);
}

static void install(void) {
    Class cls = objc_getClass("CommonComponentAccessibility");
    swizzleClassMethod(cls, @selector(createWithParent:withClass:accessible:role:index:withEnv:withView:),
                       (IMP)probe_create, &sOriginalCreate);
    swizzleClassMethod(cls, @selector(getCAccessible:withEnv:),
                       (IMP)probe_getCAccessible, &sOriginalGetCAccessible);
    swizzleClassMethod(cls, @selector(getComponentAccessibilityClass:andParent:),
                       (IMP)probe_roleClass, &sOriginalRoleClass);
    swizzleClassMethod(cls, @selector(childrenOfParent:withEnv:withChildrenCode:allowIgnored:recursive:),
                       (IMP)probe_children, &sOriginalChildren);
    swizzleClassMethod(cls, @selector(initializeRolesMap), (IMP)probe_rolesMap, &sOriginalRolesMap);
    NSLog(@"[axprobe] installed on %@", cls);
}

__attribute__((constructor)) static void axProbeStart(void) {
    [NSThread detachNewThreadWithBlock:^{
        for (int i = 0; i < 600; i++) {
            if (objc_getClass("CommonComponentAccessibility") != nil) {
                install();
                return;
            }
            [NSThread sleepForTimeInterval:0.1];
        }
        NSLog(@"[axprobe] CommonComponentAccessibility never appeared");
    }];
}

// `CAccessibility.roleKey` is the native method the Java side uses to decide whether a role
// is one the platform ignores. It is a plain C entry point rather than an Objective-C
// method, so it cannot be swizzled; instead this library exports the same symbol. The image
// resolves JNI natives with dlsym at the first call, and an inserted library comes first in
// the search order, so this wrapper is what gets bound. It forwards to the real one and
// logs the result, which says whether the ignored-role filter can work at all.
#include <dlfcn.h>
#include <jni.h>

typedef jstring (*rolekey_fn)(JNIEnv *, jclass, jobject);

JNIEXPORT jstring JNICALL Java_sun_lwawt_macosx_CAccessibility_roleKey(JNIEnv *env, jclass clz, jobject axRole) {
    static rolekey_fn real;
    if (real == NULL) {
        real = (rolekey_fn)dlsym(RTLD_NEXT, "Java_sun_lwawt_macosx_CAccessibility_roleKey");
        if (real == NULL) {
            NSLog(@"[axprobe] roleKey: no real implementation found");
            return NULL;
        }
    }
    jstring result = real(env, clz, axRole);
    if (result == NULL) {
        NSLog(@"[axprobe] roleKey -> (null)");
    } else {
        const char *chars = (*env)->GetStringUTFChars(env, result, NULL);
        NSLog(@"[axprobe] roleKey -> %s", chars == NULL ? "(no chars)" : chars);
        if (chars != NULL) (*env)->ReleaseStringUTFChars(env, result, chars);
    }
    return result;
}
