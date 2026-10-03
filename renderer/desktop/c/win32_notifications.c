/*
 * Notifications on Windows: toasts, through the Windows Runtime's ToastNotificationManager.
 *
 * The same dxc_notify_* names the macOS native image answers in macos_notifications.m, so
 * one piece of Kotlin drives both.
 *
 * A toast needs an identity for the application it belongs to, an AppUserModelID, and the
 * system shows one only for an identity it can find: a packaged application (MSIX) has one
 * by being installed, and an unpackaged one has one when a Start menu shortcut carries it.
 * The renderer does not make that shortcut, because putting things in a user's Start menu is
 * an installer's decision. It looks for one that points at this executable and takes the
 * identity from it, and it sets that identity on the process so the taskbar and the toasts
 * agree. Where there is none the answer is that notifications are unsupported in this run.
 *
 * Plain C, like the window beside it. The Windows Runtime interfaces are reached through
 * the few vtables declared below rather than through the SDK's generated headers: they are
 * fixed, published ABI, and declaring the handful of slots used here keeps this file free of
 * the C++ runtime and of the size of those headers. Each one lists its methods in the order
 * the ABI fixes, including the ones not called, because a slot is found by its position.
 *
 * Every dxc_notify_* call comes from the renderer's UI thread. A toast is activated on a
 * thread of the system's; the press is put on a list here and the renderer is asked for a
 * frame, and the frame takes it off the list on the UI thread.
 */
#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <propsys.h>
#include <roapi.h>
#include <winstring.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

void compose_rust_renderer_request_frame(void);

enum {
    DXC_NOTIFY_NONE = 0,
    DXC_NOTIFY_ACTIVATED = 1,
    DXC_NOTIFY_PERMISSION = 2,
};
enum {
    DXC_PERMISSION_NOT_DETERMINED = 1,
    DXC_PERMISSION_GRANTED = 2,
    DXC_PERMISSION_DENIED = 3,
    DXC_PERMISSION_UNSUPPORTED = 4,
};

/* ---------------------------------------------------------------------------------------
 * The Windows Runtime surface used here.
 * ------------------------------------------------------------------------------------ */

static const IID DXC_IID_IAgileObject =
    {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const IID DXC_IID_IToastNotificationManagerStatics =
    {0x50ac103f, 0xd235, 0x4598, {0xbb, 0xef, 0x98, 0xfe, 0x4d, 0x1a, 0x3a, 0xd4}};
static const IID DXC_IID_IToastNotificationManagerStatics2 =
    {0x7ab93c52, 0x0e48, 0x4750, {0xba, 0x9d, 0x1a, 0x41, 0x13, 0x98, 0x18, 0x47}};
static const IID DXC_IID_IToastNotificationFactory =
    {0x04124b20, 0x82c6, 0x4229, {0xb1, 0x09, 0xfd, 0x9e, 0xd4, 0x66, 0x2b, 0x53}};
static const IID DXC_IID_IToastNotification2 =
    {0x9dfb9fd1, 0x143a, 0x490e, {0x90, 0xbf, 0xb9, 0xfb, 0xa7, 0x13, 0x2d, 0xe7}};
static const IID DXC_IID_IToastActivatedEventArgs =
    {0xe3bf92f3, 0xc197, 0x436f, {0x82, 0x65, 0x06, 0x25, 0x82, 0x4f, 0x8d, 0xac}};
static const IID DXC_IID_IXmlDocument =
    {0xf7f3a506, 0x1e87, 0x42d6, {0xbc, 0xfb, 0xb8, 0xc8, 0x09, 0xfa, 0x54, 0x94}};
static const IID DXC_IID_IXmlDocumentIO =
    {0x6cd0e74e, 0xee65, 0x4489, {0x9e, 0xbf, 0xca, 0x43, 0xe8, 0x7b, 0xa6, 0x37}};
/* TypedEventHandler<ToastNotification, Object>, the Activated event's handler. */
static const IID DXC_IID_ActivatedHandler =
    {0xab54de2d, 0x97d9, 0x5528, {0xb6, 0xad, 0x10, 0x5a, 0xfe, 0x15, 0x65, 0x30}};
/* The classic COM names, declared here rather than taken from uuid.lib, so that what this
 * file links against is the system libraries and nothing that varies between SDKs. */
static const IID DXC_IID_IUnknown =
    {0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const CLSID DXC_CLSID_ShellLink =
    {0x00021401, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const IID DXC_IID_IShellLinkW =
    {0x000214f9, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const IID DXC_IID_IPersistFile =
    {0x0000010b, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const IID DXC_IID_IPropertyStore =
    {0x886d8eeb, 0x8cf2, 0x4446, {0x8d, 0x02, 0xcd, 0xba, 0x1d, 0xbd, 0xcf, 0x99}};
/* The user's own Start menu programs, and everyone's. */
static const KNOWNFOLDERID DXC_FOLDERID_Programs =
    {0xa77f5d77, 0x2e2b, 0x44c3, {0xa6, 0xa2, 0xab, 0xa6, 0x01, 0x05, 0x4a, 0x51}};
static const KNOWNFOLDERID DXC_FOLDERID_CommonPrograms =
    {0x0139d44e, 0x6afe, 0x49f2, {0x86, 0x90, 0x3d, 0xaf, 0xca, 0xe6, 0xff, 0xb8}};
/* System.AppUserModel.ID, the property a shortcut carries its identity in. */
static const PROPERTYKEY DXC_PKEY_AppUserModel_ID =
    {{0x9f4c2855, 0x9f79, 0x4b39, {0xa8, 0xd0, 0xe1, 0xd4, 0x2d, 0xe1, 0xd5, 0xf3}}, 5};

/* What adding an event handler answers with. The SDK declares it in eventtoken.h; it is one
 * 64-bit value, declared here so that this file needs no header beyond the C ones. */
typedef struct {
    int64_t value;
} DxcEventToken;

/* The first six slots of every Windows Runtime interface. */
#define DXC_INSPECTABLE_SLOTS(Self)                                                           \
    HRESULT(STDMETHODCALLTYPE *QueryInterface)(Self *, REFIID, void **);                    \
    ULONG(STDMETHODCALLTYPE *AddRef)(Self *);                                                \
    ULONG(STDMETHODCALLTYPE *Release)(Self *);                                               \
    void *GetIids;                                                                           \
    void *GetRuntimeClassName;                                                               \
    void *GetTrustLevel;

typedef struct DxcInspectable DxcInspectable;
typedef struct { DXC_INSPECTABLE_SLOTS(DxcInspectable) } DxcInspectableVtbl;
struct DxcInspectable { const DxcInspectableVtbl *lpVtbl; };

typedef struct DxcXmlDocumentIO DxcXmlDocumentIO;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcXmlDocumentIO)
    HRESULT(STDMETHODCALLTYPE *LoadXml)(DxcXmlDocumentIO *, HSTRING);
    void *LoadXmlWithSettings;
    void *SaveToFileAsync;
} DxcXmlDocumentIOVtbl;
struct DxcXmlDocumentIO { const DxcXmlDocumentIOVtbl *lpVtbl; };

typedef struct DxcToastNotification DxcToastNotification;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotification)
    void *get_Content;
    void *put_ExpirationTime;
    void *get_ExpirationTime;
    void *add_Dismissed;
    void *remove_Dismissed;
    HRESULT(STDMETHODCALLTYPE *add_Activated)(DxcToastNotification *, void *, DxcEventToken *);
    void *remove_Activated;
    void *add_Failed;
    void *remove_Failed;
} DxcToastNotificationVtbl;
struct DxcToastNotification { const DxcToastNotificationVtbl *lpVtbl; };

typedef struct DxcToastNotification2 DxcToastNotification2;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotification2)
    HRESULT(STDMETHODCALLTYPE *put_Tag)(DxcToastNotification2 *, HSTRING);
    void *get_Tag;
    HRESULT(STDMETHODCALLTYPE *put_Group)(DxcToastNotification2 *, HSTRING);
    void *get_Group;
    void *put_SuppressPopup;
    void *get_SuppressPopup;
} DxcToastNotification2Vtbl;
struct DxcToastNotification2 { const DxcToastNotification2Vtbl *lpVtbl; };

typedef struct DxcToastNotificationFactory DxcToastNotificationFactory;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotificationFactory)
    HRESULT(STDMETHODCALLTYPE *CreateToastNotification)(DxcToastNotificationFactory *, DxcInspectable *, DxcToastNotification **);
} DxcToastNotificationFactoryVtbl;
struct DxcToastNotificationFactory { const DxcToastNotificationFactoryVtbl *lpVtbl; };

typedef struct DxcToastNotifier DxcToastNotifier;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotifier)
    HRESULT(STDMETHODCALLTYPE *Show)(DxcToastNotifier *, DxcToastNotification *);
    void *Hide;
    HRESULT(STDMETHODCALLTYPE *get_Setting)(DxcToastNotifier *, int32_t *);
    void *AddToSchedule;
    void *RemoveFromSchedule;
    void *GetScheduledToastNotifications;
} DxcToastNotifierVtbl;
struct DxcToastNotifier { const DxcToastNotifierVtbl *lpVtbl; };

typedef struct DxcToastNotificationHistory DxcToastNotificationHistory;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotificationHistory)
    void *RemoveGroup;
    HRESULT(STDMETHODCALLTYPE *RemoveGroupWithId)(DxcToastNotificationHistory *, HSTRING, HSTRING);
    HRESULT(STDMETHODCALLTYPE *RemoveGroupedTagWithId)(DxcToastNotificationHistory *, HSTRING, HSTRING, HSTRING);
    void *RemoveGroupedTag;
    void *Remove;
    void *Clear;
    void *ClearWithId;
} DxcToastNotificationHistoryVtbl;
struct DxcToastNotificationHistory { const DxcToastNotificationHistoryVtbl *lpVtbl; };

typedef struct DxcToastNotificationManagerStatics DxcToastNotificationManagerStatics;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotificationManagerStatics)
    void *CreateToastNotifier;
    HRESULT(STDMETHODCALLTYPE *CreateToastNotifierWithId)(DxcToastNotificationManagerStatics *, HSTRING, DxcToastNotifier **);
    void *GetTemplateContent;
} DxcToastNotificationManagerStaticsVtbl;
struct DxcToastNotificationManagerStatics { const DxcToastNotificationManagerStaticsVtbl *lpVtbl; };

typedef struct DxcToastNotificationManagerStatics2 DxcToastNotificationManagerStatics2;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastNotificationManagerStatics2)
    HRESULT(STDMETHODCALLTYPE *get_History)(DxcToastNotificationManagerStatics2 *, DxcToastNotificationHistory **);
} DxcToastNotificationManagerStatics2Vtbl;
struct DxcToastNotificationManagerStatics2 { const DxcToastNotificationManagerStatics2Vtbl *lpVtbl; };

typedef struct DxcToastActivatedEventArgs DxcToastActivatedEventArgs;
typedef struct {
    DXC_INSPECTABLE_SLOTS(DxcToastActivatedEventArgs)
    HRESULT(STDMETHODCALLTYPE *get_Arguments)(DxcToastActivatedEventArgs *, HSTRING *);
} DxcToastActivatedEventArgsVtbl;
struct DxcToastActivatedEventArgs { const DxcToastActivatedEventArgsVtbl *lpVtbl; };

#define DXC_RELEASE(object)                                                                   \
    do {                                                                                     \
        if ((object) != NULL) {                                                              \
            (object)->lpVtbl->Release(object);                                               \
            (object) = NULL;                                                                 \
        }                                                                                    \
    } while (0)

/* ---------------------------------------------------------------------------------------
 * What the UI thread reads back.
 * ------------------------------------------------------------------------------------ */

typedef struct dxc_notify_event {
    int32_t kind;
    int32_t value;
    char *key;
    struct dxc_notify_event *next;
} dxc_notify_event;

static SRWLOCK dxc_notify_lock = SRWLOCK_INIT;
static dxc_notify_event *dxc_notify_head;
static dxc_notify_event *dxc_notify_tail;

/* Queues one answer and asks for the frame that delivers it. Any thread. Takes `key`. */
static void dxc_notify_push(int32_t kind, int32_t value, char *key) {
    dxc_notify_event *event = calloc(1, sizeof(dxc_notify_event));
    if (event == NULL) {
        free(key);
        return;
    }
    event->kind = kind;
    event->value = value;
    event->key = key;
    AcquireSRWLockExclusive(&dxc_notify_lock);
    if (dxc_notify_tail == NULL) {
        dxc_notify_head = event;
    } else {
        dxc_notify_tail->next = event;
    }
    dxc_notify_tail = event;
    ReleaseSRWLockExclusive(&dxc_notify_lock);
    compose_rust_renderer_request_frame();
}

/* ---------------------------------------------------------------------------------------
 * Strings.
 * ------------------------------------------------------------------------------------ */

/* UTF-8 to a freshly allocated UTF-16 string. Never null for a non-null argument. */
static wchar_t *dxc_wide(const char *text) {
    if (text == NULL) text = "";
    int length = MultiByteToWideChar(CP_UTF8, 0, text, -1, NULL, 0);
    if (length <= 0) length = 1;
    wchar_t *wide = calloc((size_t)length, sizeof(wchar_t));
    if (wide != NULL && length > 1) MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, length);
    return wide;
}

/* UTF-16 to a freshly allocated UTF-8 string. */
static char *dxc_narrow(const wchar_t *text, UINT32 length) {
    int bytes = WideCharToMultiByte(CP_UTF8, 0, text, (int)length, NULL, 0, NULL, NULL);
    char *narrow = calloc((size_t)(bytes > 0 ? bytes : 0) + 1, 1);
    if (narrow != NULL && bytes > 0) {
        WideCharToMultiByte(CP_UTF8, 0, text, (int)length, narrow, bytes, NULL, NULL);
    }
    return narrow;
}

static HSTRING dxc_hstring(const wchar_t *text) {
    HSTRING value = NULL;
    WindowsCreateString(text, (UINT32)wcslen(text), &value);
    return value;
}

/* A growable UTF-16 buffer, for the toast's XML. */
typedef struct {
    wchar_t *text;
    size_t length;
    size_t capacity;
} dxc_text;

static void dxc_append(dxc_text *out, const wchar_t *text) {
    size_t more = wcslen(text);
    if (out->length + more + 1 > out->capacity) {
        size_t capacity = (out->length + more + 1) * 2;
        wchar_t *grown = realloc(out->text, capacity * sizeof(wchar_t));
        if (grown == NULL) return;
        out->text = grown;
        out->capacity = capacity;
    }
    memcpy(out->text + out->length, text, more * sizeof(wchar_t));
    out->length += more;
    out->text[out->length] = L'\0';
}

/* Text as XML character data or an attribute value. */
static void dxc_append_escaped(dxc_text *out, const wchar_t *text) {
    wchar_t one[2] = {0, 0};
    for (; *text != L'\0'; text++) {
        switch (*text) {
        case L'&': dxc_append(out, L"&amp;"); break;
        case L'<': dxc_append(out, L"&lt;"); break;
        case L'>': dxc_append(out, L"&gt;"); break;
        case L'"': dxc_append(out, L"&quot;"); break;
        case L'\'': dxc_append(out, L"&apos;"); break;
        default:
            one[0] = *text;
            dxc_append(out, one);
        }
    }
}

/*
 * The tag a key is shown under.
 *
 * A tag is limited to 64 characters, and a key is the application's to choose. A longer
 * key is shown under a hash of itself, which the same key always gives again, so replacing
 * and withdrawing still find it. The key itself travels in the activation arguments, which
 * have no such limit, so a press still reports the key the application gave.
 */
static void dxc_tag(const wchar_t *key, wchar_t *tag, size_t capacity) {
    size_t length = wcslen(key);
    if (length <= 64 && length < capacity) {
        wcscpy_s(tag, capacity, key);
        return;
    }
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t index = 0; index < length; index++) {
        hash ^= (uint64_t)key[index];
        hash *= 0x100000001b3ull;
    }
    swprintf(tag, capacity, L"compose-rust-%016llx", (unsigned long long)hash);
}

/* ---------------------------------------------------------------------------------------
 * State. Touched only on the UI thread, except the event list above.
 * ------------------------------------------------------------------------------------ */

static const wchar_t DXC_GROUP[] = L"compose-rust";

static int dxc_started;
static int32_t dxc_state = DXC_PERMISSION_UNSUPPORTED;
static wchar_t dxc_app_id[1024];
static DxcToastNotificationManagerStatics *dxc_manager;
static DxcToastNotificationHistory *dxc_history;
static DxcToastNotificationFactory *dxc_factory;
static DxcToastNotifier *dxc_notifier;

/*
 * The toasts this process showed, by tag.
 *
 * Kept because the Activated event belongs to the toast object: a toast nobody holds a
 * reference to is a toast whose press is never heard. Released when it is replaced,
 * withdrawn or taken back at exit.
 */
typedef struct dxc_shown {
    wchar_t tag[80];
    DxcToastNotification *toast;
    struct dxc_shown *next;
} dxc_shown;
static dxc_shown *dxc_shown_list;

static void dxc_forget(const wchar_t *tag) {
    dxc_shown **link = &dxc_shown_list;
    while (*link != NULL) {
        dxc_shown *entry = *link;
        if (tag == NULL || wcscmp(entry->tag, tag) == 0) {
            *link = entry->next;
            DXC_RELEASE(entry->toast);
            free(entry);
        } else {
            link = &entry->next;
        }
    }
}

/* ---------------------------------------------------------------------------------------
 * The application's window, for a press on a toast's body.
 * ------------------------------------------------------------------------------------ */

static BOOL CALLBACK dxc_find_window(HWND window, LPARAM found) {
    DWORD process = 0;
    GetWindowThreadProcessId(window, &process);
    if (process == GetCurrentProcessId() && IsWindowVisible(window) &&
        GetWindow(window, GW_OWNER) == NULL) {
        *(HWND *)found = window;
        return FALSE;
    }
    return TRUE;
}

/* Brings the window up, restoring it if it was minimised. A button does not do this. */
static void dxc_bring_to_front(void) {
    HWND window = NULL;
    EnumWindows(dxc_find_window, (LPARAM)&window);
    if (window == NULL) return;
    if (IsIconic(window)) ShowWindow(window, SW_RESTORE);
    SetForegroundWindow(window);
}

/* ---------------------------------------------------------------------------------------
 * The Activated handler: one static object, alive for the life of the process.
 * ------------------------------------------------------------------------------------ */

typedef struct DxcActivatedHandler DxcActivatedHandler;
typedef struct {
    HRESULT(STDMETHODCALLTYPE *QueryInterface)(DxcActivatedHandler *, REFIID, void **);
    ULONG(STDMETHODCALLTYPE *AddRef)(DxcActivatedHandler *);
    ULONG(STDMETHODCALLTYPE *Release)(DxcActivatedHandler *);
    HRESULT(STDMETHODCALLTYPE *Invoke)(DxcActivatedHandler *, DxcInspectable *, DxcInspectable *);
} DxcActivatedHandlerVtbl;
struct DxcActivatedHandler { const DxcActivatedHandlerVtbl *lpVtbl; };

static HRESULT STDMETHODCALLTYPE dxc_handler_query(DxcActivatedHandler *self, REFIID iid, void **out) {
    if (IsEqualIID(iid, &DXC_IID_IUnknown) || IsEqualIID(iid, &DXC_IID_IAgileObject) ||
        IsEqualIID(iid, &DXC_IID_ActivatedHandler)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

/* Static, so it is never freed and counting means nothing. */
static ULONG STDMETHODCALLTYPE dxc_handler_add_ref(DxcActivatedHandler *self) { return 2; }
static ULONG STDMETHODCALLTYPE dxc_handler_release(DxcActivatedHandler *self) { return 1; }

/*
 * A press. The arguments are what the toast was given: "<action>:<key>", where the action
 * is 0 for the body and 1 or 2 for a button.
 */
static HRESULT STDMETHODCALLTYPE dxc_handler_invoke(DxcActivatedHandler *self, DxcInspectable *sender,
                                                    DxcInspectable *args) {
    if (args == NULL) return S_OK;
    DxcToastActivatedEventArgs *activated = NULL;
    if (FAILED(args->lpVtbl->QueryInterface(args, &DXC_IID_IToastActivatedEventArgs, (void **)&activated))) {
        return S_OK;
    }
    HSTRING arguments = NULL;
    if (SUCCEEDED(activated->lpVtbl->get_Arguments(activated, &arguments)) && arguments != NULL) {
        UINT32 length = 0;
        const wchar_t *text = WindowsGetStringRawBuffer(arguments, &length);
        if (length >= 2 && text[0] >= L'0' && text[0] <= L'2' && text[1] == L':') {
            int32_t action = (int32_t)(text[0] - L'0');
            if (action == 0) dxc_bring_to_front();
            dxc_notify_push(DXC_NOTIFY_ACTIVATED, action, dxc_narrow(text + 2, length - 2));
        }
        WindowsDeleteString(arguments);
    }
    DXC_RELEASE(activated);
    return S_OK;
}

static const DxcActivatedHandlerVtbl dxc_handler_vtbl = {
    dxc_handler_query, dxc_handler_add_ref, dxc_handler_release, dxc_handler_invoke,
};
static DxcActivatedHandler dxc_handler = {&dxc_handler_vtbl};

/* ---------------------------------------------------------------------------------------
 * Finding this application's identity.
 * ------------------------------------------------------------------------------------ */

typedef LONG(WINAPI *dxc_current_app_id_fn)(UINT32 *, PWSTR);

/* A packaged application's identity, which installing it gave it. */
static int dxc_packaged_identity(void) {
    HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    dxc_current_app_id_fn current = kernel == NULL ? NULL
        : (dxc_current_app_id_fn)GetProcAddress(kernel, "GetCurrentApplicationUserModelId");
    if (current == NULL) return 0;
    UINT32 length = (UINT32)(sizeof(dxc_app_id) / sizeof(wchar_t));
    return current(&length, dxc_app_id) == ERROR_SUCCESS && dxc_app_id[0] != L'\0';
}

/* The identity a shortcut carries, when the shortcut points at `executable`. */
static int dxc_shortcut_identity(const wchar_t *path, const wchar_t *executable) {
    int found = 0;
    IShellLinkW *link = NULL;
    IPersistFile *file = NULL;
    IPropertyStore *store = NULL;
    if (FAILED(CoCreateInstance(&DXC_CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER,
                                &DXC_IID_IShellLinkW, (void **)&link))) {
        return 0;
    }
    if (SUCCEEDED(IShellLinkW_QueryInterface(link, &DXC_IID_IPersistFile, (void **)&file)) &&
        SUCCEEDED(IPersistFile_Load(file, path, STGM_READ))) {
        wchar_t target[MAX_PATH];
        if (SUCCEEDED(IShellLinkW_GetPath(link, target, MAX_PATH, NULL, SLGP_RAWPATH)) &&
            _wcsicmp(target, executable) == 0 &&
            SUCCEEDED(IShellLinkW_QueryInterface(link, &DXC_IID_IPropertyStore, (void **)&store))) {
            PROPVARIANT value;
            PropVariantInit(&value);
            if (SUCCEEDED(IPropertyStore_GetValue(store, &DXC_PKEY_AppUserModel_ID, &value)) &&
                value.vt == VT_LPWSTR && value.pwszVal != NULL && value.pwszVal[0] != L'\0') {
                wcscpy_s(dxc_app_id, sizeof(dxc_app_id) / sizeof(wchar_t), value.pwszVal);
                found = 1;
            }
            PropVariantClear(&value);
        }
    }
    if (store != NULL) IPropertyStore_Release(store);
    if (file != NULL) IPersistFile_Release(file);
    IShellLinkW_Release(link);
    return found;
}

/* Searches one Start menu tree for a shortcut to this executable that carries an identity. */
static int dxc_search_shortcuts(const wchar_t *directory, const wchar_t *executable, int depth) {
    if (depth > 4) return 0;
    wchar_t pattern[MAX_PATH];
    if (swprintf(pattern, MAX_PATH, L"%ls\\*", directory) < 0) return 0;
    WIN32_FIND_DATAW entry;
    HANDLE search = FindFirstFileW(pattern, &entry);
    if (search == INVALID_HANDLE_VALUE) return 0;
    int found = 0;
    do {
        if (wcscmp(entry.cFileName, L".") == 0 || wcscmp(entry.cFileName, L"..") == 0) continue;
        wchar_t path[MAX_PATH];
        if (swprintf(path, MAX_PATH, L"%ls\\%ls", directory, entry.cFileName) < 0) continue;
        if (entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            found = dxc_search_shortcuts(path, executable, depth + 1);
        } else {
            size_t length = wcslen(entry.cFileName);
            if (length > 4 && _wcsicmp(entry.cFileName + length - 4, L".lnk") == 0) {
                found = dxc_shortcut_identity(path, executable);
            }
        }
    } while (!found && FindNextFileW(search, &entry));
    FindClose(search);
    return found;
}

/*
 * An unpackaged application's identity: the one on a Start menu shortcut that points at
 * this executable, the user's own Start menu first and then everyone's.
 */
static int dxc_unpackaged_identity(void) {
    wchar_t executable[MAX_PATH];
    DWORD length = GetModuleFileNameW(NULL, executable, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return 0;
    const KNOWNFOLDERID *folders[] = {&DXC_FOLDERID_Programs, &DXC_FOLDERID_CommonPrograms};
    for (size_t index = 0; index < sizeof(folders) / sizeof(folders[0]); index++) {
        PWSTR directory = NULL;
        if (SUCCEEDED(SHGetKnownFolderPath(folders[index], 0, NULL, &directory))) {
            int found = dxc_search_shortcuts(directory, executable, 0);
            CoTaskMemFree(directory);
            if (found) return 1;
        }
    }
    return 0;
}

static int32_t dxc_current_state(void) {
    if (dxc_notifier == NULL) return DXC_PERMISSION_UNSUPPORTED;
    int32_t setting = 0;
    if (FAILED(dxc_notifier->lpVtbl->get_Setting(dxc_notifier, &setting))) {
        return DXC_PERMISSION_GRANTED;
    }
    /* Enabled is zero. The others are the user, the application's own settings page, a
     * group policy or the manifest turning toasts off, and each is a no. */
    return setting == 0 ? DXC_PERMISSION_GRANTED : DXC_PERMISSION_DENIED;
}

/* ---------------------------------------------------------------------------------------
 * The names the renderer calls.
 * ------------------------------------------------------------------------------------ */

int32_t dxc_notify_start(void) {
    if (dxc_started) return dxc_state;
    dxc_started = 1;
    /* Either apartment will do. One the toolkit already chose for this thread answers that
     * it was chosen differently, which is not a failure. */
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    RoInitialize(RO_INIT_SINGLETHREADED);

    int identified = dxc_packaged_identity();
    if (!identified) {
        identified = dxc_unpackaged_identity();
        /* The process wears the shortcut's identity, so its toasts and its taskbar button
         * are the application the shortcut names. */
        if (identified) SetCurrentProcessExplicitAppUserModelID(dxc_app_id);
    }
    if (!identified) return dxc_state = DXC_PERMISSION_UNSUPPORTED;

    HSTRING manager_name = dxc_hstring(L"Windows.UI.Notifications.ToastNotificationManager");
    HSTRING toast_name = dxc_hstring(L"Windows.UI.Notifications.ToastNotification");
    HSTRING app_id = dxc_hstring(dxc_app_id);
    DxcToastNotificationManagerStatics2 *statics2 = NULL;
    if (SUCCEEDED(RoGetActivationFactory(manager_name, &DXC_IID_IToastNotificationManagerStatics,
                                         (void **)&dxc_manager)) &&
        SUCCEEDED(RoGetActivationFactory(toast_name, &DXC_IID_IToastNotificationFactory,
                                         (void **)&dxc_factory)) &&
        SUCCEEDED(dxc_manager->lpVtbl->CreateToastNotifierWithId(dxc_manager, app_id, &dxc_notifier))) {
        if (SUCCEEDED(dxc_manager->lpVtbl->QueryInterface(
                dxc_manager, &DXC_IID_IToastNotificationManagerStatics2, (void **)&statics2))) {
            statics2->lpVtbl->get_History(statics2, &dxc_history);
            DXC_RELEASE(statics2);
        }
    } else {
        DXC_RELEASE(dxc_notifier);
        DXC_RELEASE(dxc_factory);
        DXC_RELEASE(dxc_manager);
    }
    WindowsDeleteString(manager_name);
    WindowsDeleteString(toast_name);
    WindowsDeleteString(app_id);
    return dxc_state = dxc_current_state();
}

/* There is no prompt on this platform: toasts are on until the user turns them off. The
 * answer is whatever the setting says now. */
void dxc_notify_request_permission(void) {
    dxc_state = dxc_current_state();
    dxc_notify_push(DXC_NOTIFY_PERMISSION, dxc_state, NULL);
}

void dxc_notify_refresh_permission(void) {
    if (!dxc_started) return;
    dxc_state = dxc_current_state();
    dxc_notify_push(DXC_NOTIFY_PERMISSION, dxc_state, NULL);
}

void dxc_notify_post(const char *key, const char *title, const char *body, const char *channel,
                     const char *action1, const char *action2, int32_t urgent) {
    if (dxc_notifier == NULL || dxc_factory == NULL) return;
    wchar_t *wide_key = dxc_wide(key);
    wchar_t *wide_title = dxc_wide(title);
    wchar_t *wide_body = dxc_wide(body);
    wchar_t *wide_action1 = dxc_wide(action1);
    wchar_t *wide_action2 = dxc_wide(action2);
    if (wide_key == NULL || wide_title == NULL || wide_body == NULL || wide_action1 == NULL ||
        wide_action2 == NULL) {
        goto done;
    }

    /* The body's arguments and each button's carry which part was pressed and the key. The
     * buttons are foreground activations so that the press is heard in this process; the
     * window is brought up only for the body, by the handler, not by the system. */
    dxc_text xml = {0};
    dxc_append(&xml, L"<toast launch=\"0:");
    dxc_append_escaped(&xml, wide_key);
    dxc_append(&xml, L"\"><visual><binding template=\"ToastGeneric\"><text>");
    dxc_append_escaped(&xml, wide_title);
    dxc_append(&xml, L"</text>");
    if (wide_body[0] != L'\0') {
        dxc_append(&xml, L"<text>");
        dxc_append_escaped(&xml, wide_body);
        dxc_append(&xml, L"</text>");
    }
    dxc_append(&xml, L"</binding></visual>");
    if (wide_action1[0] != L'\0' || wide_action2[0] != L'\0') {
        dxc_append(&xml, L"<actions>");
        const wchar_t *labels[2] = {wide_action1, wide_action2};
        const wchar_t *numbers[2] = {L"1", L"2"};
        for (int index = 0; index < 2; index++) {
            if (labels[index][0] == L'\0') continue;
            dxc_append(&xml, L"<action activationType=\"foreground\" content=\"");
            dxc_append_escaped(&xml, labels[index]);
            dxc_append(&xml, L"\" arguments=\"");
            dxc_append(&xml, numbers[index]);
            dxc_append(&xml, L":");
            dxc_append_escaped(&xml, wide_key);
            dxc_append(&xml, L"\"/>");
        }
        dxc_append(&xml, L"</actions>");
    }
    /* Urgent is the reminder sound rather than the ordinary one. */
    dxc_append(&xml, urgent ? L"<audio src=\"ms-winsoundevent:Notification.Reminder\"/>"
                            : L"<audio src=\"ms-winsoundevent:Notification.Default\"/>");
    dxc_append(&xml, L"</toast>");
    if (xml.text == NULL) goto done;

    DxcInspectable *document = NULL;
    DxcXmlDocumentIO *io = NULL;
    DxcInspectable *xml_document = NULL;
    DxcToastNotification *toast = NULL;
    DxcToastNotification2 *toast2 = NULL;
    HSTRING document_name = dxc_hstring(L"Windows.Data.Xml.Dom.XmlDocument");
    HSTRING source = dxc_hstring(xml.text);
    wchar_t tag[80];
    dxc_tag(wide_key, tag, sizeof(tag) / sizeof(wchar_t));
    HSTRING tag_string = dxc_hstring(tag);
    HSTRING group_string = dxc_hstring(DXC_GROUP);
    if (SUCCEEDED(RoActivateInstance(document_name, (IInspectable **)&document)) &&
        SUCCEEDED(document->lpVtbl->QueryInterface(document, &DXC_IID_IXmlDocumentIO, (void **)&io)) &&
        SUCCEEDED(io->lpVtbl->LoadXml(io, source)) &&
        SUCCEEDED(document->lpVtbl->QueryInterface(document, &DXC_IID_IXmlDocument, (void **)&xml_document)) &&
        SUCCEEDED(dxc_factory->lpVtbl->CreateToastNotification(dxc_factory, xml_document, &toast))) {
        /* The tag and group are what a second post under the same key replaces, and what
         * withdrawing it removes. */
        if (SUCCEEDED(toast->lpVtbl->QueryInterface(toast, &DXC_IID_IToastNotification2, (void **)&toast2))) {
            toast2->lpVtbl->put_Tag(toast2, tag_string);
            toast2->lpVtbl->put_Group(toast2, group_string);
        }
        DxcEventToken token;
        toast->lpVtbl->add_Activated(toast, &dxc_handler, &token);
        if (SUCCEEDED(dxc_notifier->lpVtbl->Show(dxc_notifier, toast))) {
            dxc_forget(tag);
            dxc_shown *entry = calloc(1, sizeof(dxc_shown));
            if (entry != NULL) {
                wcscpy_s(entry->tag, sizeof(entry->tag) / sizeof(wchar_t), tag);
                entry->toast = toast;
                toast = NULL;
                entry->next = dxc_shown_list;
                dxc_shown_list = entry;
            }
        }
    }
    DXC_RELEASE(toast2);
    DXC_RELEASE(toast);
    DXC_RELEASE(xml_document);
    DXC_RELEASE(io);
    DXC_RELEASE(document);
    WindowsDeleteString(document_name);
    WindowsDeleteString(source);
    WindowsDeleteString(tag_string);
    WindowsDeleteString(group_string);
    free(xml.text);

done:
    free(wide_key);
    free(wide_title);
    free(wide_body);
    free(wide_action1);
    free(wide_action2);
    (void)channel;
}

void dxc_notify_withdraw(const char *key) {
    if (dxc_history == NULL) return;
    wchar_t *wide_key = dxc_wide(key);
    if (wide_key == NULL) return;
    wchar_t tag[80];
    dxc_tag(wide_key, tag, sizeof(tag) / sizeof(wchar_t));
    HSTRING tag_string = dxc_hstring(tag);
    HSTRING group_string = dxc_hstring(DXC_GROUP);
    HSTRING app_id = dxc_hstring(dxc_app_id);
    dxc_history->lpVtbl->RemoveGroupedTagWithId(dxc_history, tag_string, group_string, app_id);
    WindowsDeleteString(tag_string);
    WindowsDeleteString(group_string);
    WindowsDeleteString(app_id);
    dxc_forget(tag);
    free(wide_key);
}

/* The process is ending: what it showed is taken out of the action centre with it. */
void dxc_notify_withdraw_all(void) {
    if (dxc_history == NULL) return;
    HSTRING group_string = dxc_hstring(DXC_GROUP);
    HSTRING app_id = dxc_hstring(dxc_app_id);
    dxc_history->lpVtbl->RemoveGroupWithId(dxc_history, group_string, app_id);
    WindowsDeleteString(group_string);
    WindowsDeleteString(app_id);
    dxc_forget(NULL);
}

/* Takes the oldest answer off the list; see macos_notifications.m for the contract. */
int32_t dxc_notify_next_event(char *key, int32_t capacity, int32_t *value) {
    AcquireSRWLockExclusive(&dxc_notify_lock);
    dxc_notify_event *event = dxc_notify_head;
    if (event != NULL) {
        dxc_notify_head = event->next;
        if (dxc_notify_head == NULL) dxc_notify_tail = NULL;
    }
    ReleaseSRWLockExclusive(&dxc_notify_lock);
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
