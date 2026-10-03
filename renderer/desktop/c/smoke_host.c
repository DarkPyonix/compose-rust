/*
 * Stand-in for the Rust Host: links the renderer library and runs it, the way
 * `compose_rust::launch` does with LoopMode::Renderer.
 *
 * It also implements the `compose_rust_host_*` half of the boundary, because the renderer
 * resolves those symbols from the executable it is loaded into and calls `host_init` as soon
 * as the window composes. The batch it returns is hand-encoded with the same fixed-layout
 * records the Rust Host emits, so the smoke test exercises the real decode path.
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define HOST_EXPORT __declspec(dllexport)
#else
#include <time.h>
#define HOST_EXPORT
#endif

int32_t compose_rust_renderer_run(void);
void compose_rust_renderer_request_frame(void);

/* One answer to a measure request, as the renderer writes it. */
typedef struct {
    float width, height, first_baseline, last_baseline, last_line_width;
    uint32_t line_count, flags, status;
} MeasureResult;

int32_t compose_rust_renderer_measure(
    const uint8_t *requests, uint32_t len, uint32_t count, MeasureResult *results
);

typedef struct {
    const uint8_t *ptr;
    uint32_t len;
    int64_t result;
} MutationBatch;

enum { STATUS_OK = 0, STATUS_PROTOCOL_ERROR = -1 };

/* Mutation tags (schema.rs). */
enum { TAG_ENVELOPE = 0, TAG_CREATE = 1, TAG_SET_PROP = 2, TAG_INSERT = 4 };
/* WidgetKind tags. */
enum {
    WIDGET_COLUMN = 1, WIDGET_TEXT = 4, WIDGET_TEXT_FIELD = 5, WIDGET_BUTTON = 6,
    WIDGET_NAVIGATION = 30, WIDGET_NAVIGATION_ITEM = 31
};
/* PropertyKind tags. */
enum {
    PROP_TEXT = 1, PROP_ON_CLICK = 5, PROP_TYPE_ROLE = 13, PROP_FONT_SIZE = 14,
    PROP_SELECTED_INDEX = 42, PROP_ICON = 60, PROP_FONT = 100
};
/* PropertyValue tags. */
enum { VALUE_TEXT = 1, VALUE_INTEGER = 3, VALUE_FLOAT = 4, VALUE_BYTES = 5 };

#define CLICK_HANDLER 7
#define LABEL_NODE 2

/* Destinations. `IconRole` is sent one higher than its tag so that zero means "not sent". */
#define NAVIGATION_NODE 10
#define HOME_NODE 11
#define SETTINGS_NODE 12
#define HOME_HANDLER 8
#define SETTINGS_HANDLER 9
#define ICON_SETTINGS 7
#define ICON_HOME 9
#define HOME_LABEL "Home"
#define SETTINGS_LABEL "Settings"

static uint8_t batch_bytes[1024];
static uint32_t batch_length;
static uint32_t records_length;
static uint32_t record_count;

static void put_u16(uint32_t offset, uint16_t value) {
    memcpy(batch_bytes + offset, &value, sizeof value);
}

static void put_u32(uint32_t offset, uint32_t value) {
    memcpy(batch_bytes + offset, &value, sizeof value);
}

static void put_u64(uint32_t offset, uint64_t value) {
    memcpy(batch_bytes + offset, &value, sizeof value);
}

static void begin_batch(void) {
    memset(batch_bytes, 0, sizeof batch_bytes);
    records_length = 12;
    record_count = 0;
}

static uint32_t begin_record(uint16_t tag, uint16_t length) {
    uint32_t offset = records_length;
    put_u16(offset, tag);
    put_u16(offset + 2, length);
    records_length += length;
    record_count += 1;
    return offset;
}

/* Strings live after the records in the same buffer, addressed by (offset, length) pairs.
   Keeping them in the batch is what lets the renderer read them in place. */
static void put_string(uint32_t reference_offset, const char *text) {
    uint32_t length = (uint32_t)strlen(text);
    memcpy(batch_bytes + batch_length, text, length);
    put_u32(reference_offset, batch_length);
    put_u32(reference_offset + 4, length);
    batch_length += length;
}

static void create(uint32_t node_id, uint16_t widget) {
    uint32_t offset = begin_record(TAG_CREATE, 12);
    put_u32(offset + 4, node_id);
    put_u16(offset + 8, widget);
}

static void set_text_prop(uint32_t node_id, const char *text) {
    uint32_t offset = begin_record(TAG_SET_PROP, 24);
    put_u32(offset + 4, node_id);
    put_u16(offset + 8, PROP_TEXT);
    put_u16(offset + 10, VALUE_TEXT);
    /* Deferred until the record area is closed; recorded here, filled in end_batch. */
    put_u32(offset + 12, 0);
    put_u32(offset + 16, (uint32_t)strlen(text));
}

static void set_handler(uint32_t node_id, uint16_t property, uint64_t handler_id) {
    uint32_t offset = begin_record(TAG_SET_PROP, 24);
    put_u32(offset + 4, node_id);
    put_u16(offset + 8, property);
    put_u16(offset + 10, VALUE_INTEGER);
    put_u64(offset + 12, handler_id);
}

static void insert(uint32_t parent_id, uint32_t node_id, uint32_t index) {
    uint32_t offset = begin_record(TAG_INSERT, 16);
    put_u32(offset + 4, parent_id);
    put_u32(offset + 8, node_id);
    put_u32(offset + 12, index);
}

static void end_batch(void) {
    put_u16(0, TAG_ENVELOPE);
    put_u16(2, 12);
    put_u32(4, records_length);
    put_u32(8, record_count);
}

/*
 * Rebuilds the whole batch so that the text records point at string bytes placed after the
 * record area. Records are laid out first, then the strings are appended in order.
 */
#define IME_FIELD_TEXT "type Korean here"

/*
 * COMPOSE_RUST_SMOKE_NAVIGATION=1 wraps the screen in a navigation with two destinations.
 *
 * Off by default for the same reason the text field is: the byte counts recorded for the
 * default batch have to stay what they were. It is on for the run that photographs the
 * chrome, because a navigation is the only declaration that reaches the system's tab bar,
 * and the default tree has none.
 */
static int want_navigation(void) {
    const char *flag = getenv("COMPOSE_RUST_SMOKE_NAVIGATION");
    return flag != NULL && flag[0] == '1' && flag[1] == '\0';
}

/* COMPOSE_RUST_SMOKE_IME=1 adds the text field on any platform. */
static int want_text_field(void) {
#ifdef __linux__
    return 1;
#else
    const char *flag = getenv("COMPOSE_RUST_SMOKE_IME");
    return flag != NULL && flag[0] == '1' && flag[1] == '\0';
#endif
}

static void build_tree(const char *label) {
    begin_batch();
    create(1, WIDGET_COLUMN);
    create(LABEL_NODE, WIDGET_TEXT);
    uint32_t label_prop = records_length;
    set_text_prop(LABEL_NODE, label);
    insert(1, LABEL_NODE, 0);
    create(3, WIDGET_BUTTON);
    uint32_t button_prop = records_length;
    set_text_prop(3, "click me");
    set_handler(3, PROP_ON_CLICK, CLICK_HANDLER);
    insert(1, 3, 1);
    /* An editable control, for the manual IME checklist (typing Korean and watching the
       composition). It is off by default so the byte counts recorded for this batch stay
       stable, and because CI has nobody to type. Linux turns it on unconditionally: a real desktop run there is the only
       way to exercise XIM through ibus or fcitx. */
    uint32_t field_prop = 0;
    if (want_text_field()) {
        create(4, WIDGET_TEXT_FIELD);
        field_prop = records_length;
        set_text_prop(4, IME_FIELD_TEXT);
        insert(1, 4, 2);
    }
    uint32_t home_prop = 0;
    uint32_t settings_prop = 0;
    if (want_navigation()) {
        create(NAVIGATION_NODE, WIDGET_NAVIGATION);
        set_handler(NAVIGATION_NODE, PROP_SELECTED_INDEX, 0);
        create(HOME_NODE, WIDGET_NAVIGATION_ITEM);
        home_prop = records_length;
        set_text_prop(HOME_NODE, HOME_LABEL);
        set_handler(HOME_NODE, PROP_ICON, ICON_HOME);
        set_handler(HOME_NODE, PROP_ON_CLICK, HOME_HANDLER);
        insert(NAVIGATION_NODE, HOME_NODE, 0);
        create(SETTINGS_NODE, WIDGET_NAVIGATION_ITEM);
        settings_prop = records_length;
        set_text_prop(SETTINGS_NODE, SETTINGS_LABEL);
        set_handler(SETTINGS_NODE, PROP_ICON, ICON_SETTINGS);
        set_handler(SETTINGS_NODE, PROP_ON_CLICK, SETTINGS_HANDLER);
        insert(NAVIGATION_NODE, SETTINGS_NODE, 1);
        /* The column built above becomes the screen the selection leads to. */
        insert(NAVIGATION_NODE, 1, 2);
    }
    end_batch();
    batch_length = records_length;
    put_string(label_prop + 12, label);
    put_string(button_prop + 12, "click me");
    if (field_prop != 0) {
        put_string(field_prop + 12, IME_FIELD_TEXT);
    }
    if (home_prop != 0) {
        put_string(home_prop + 12, HOME_LABEL);
        put_string(settings_prop + 12, SETTINGS_LABEL);
    }
}

static void build_label_update(const char *label) {
    begin_batch();
    uint32_t label_prop = records_length;
    set_text_prop(LABEL_NODE, label);
    end_batch();
    batch_length = records_length;
    put_string(label_prop + 12, label);
}

/*
 * The measure round trip, from inside frames after the tree is applied.
 *
 * The first frame adds a Text with no role, set in a generic sans serif at 16, the way a
 * page laid out by CSS writes one. The next measures four things in one call: the label's
 * string as text and the label node itself, then the new text the same two ways. Each
 * pair has to be the same numbers, bit for bit: text is measured with the font, the
 * density and the type scale it is drawn with, and the node is measured by the
 * renderer's own layout.
 */
#define MEASURE_RECORD 72
#define MEASURE_WIDTH 1000.0f
#define HTML_NODE 6
#define HTML_TEXT "Measured as CSS text"
static const char measured_label[] = "smoke host: 0 clicks";
static int frames_served;

/* A font list blob: one candidate, the generic sans serif. */
static const uint8_t html_font[16] = {1, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0};

static void put_u32_at(uint8_t *bytes, uint32_t offset, uint32_t value) {
    memcpy(bytes + offset, &value, sizeof value);
}

static void put_f32_at(uint8_t *bytes, uint32_t offset, float value) {
    memcpy(bytes + offset, &value, sizeof value);
}

static void set_float(uint32_t node_id, uint16_t property, float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof bits);
    set_handler(node_id, property, bits);
    /* set_handler wrote an integer; the same record with the float tag. */
    put_u16(records_length - 24 + 10, VALUE_FLOAT);
}

/* The batch that adds the text with no role. */
static void build_html_text(void) {
    begin_batch();
    create(HTML_NODE, WIDGET_TEXT);
    uint32_t text_prop = records_length;
    set_text_prop(HTML_NODE, HTML_TEXT);
    set_handler(HTML_NODE, PROP_TYPE_ROLE, 0);
    set_float(HTML_NODE, PROP_FONT_SIZE, 16.0f);
    uint32_t font_prop = begin_record(TAG_SET_PROP, 24);
    put_u32(font_prop + 4, HTML_NODE);
    put_u16(font_prop + 8, PROP_FONT);
    put_u16(font_prop + 10, VALUE_BYTES);
    insert(1, HTML_NODE, want_text_field() ? 3 : 2);
    end_batch();
    batch_length = records_length;
    put_string(text_prop + 12, HTML_TEXT);
    memcpy(batch_bytes + batch_length, html_font, sizeof html_font);
    put_u32(font_prop + 12, batch_length);
    put_u32(font_prop + 16, sizeof html_font);
    batch_length += sizeof html_font;
}

/* A MeasureText record for `text` at `text_at`, with the given role and size. */
static void text_request(
    uint8_t *record, uint32_t text_at, uint32_t text_length, uint8_t role, float size,
    uint32_t font_at, uint32_t font_count, uint32_t empty_at
) {
    record[0] = 1;
    record[2] = role;
    put_u32_at(record, 4, text_at);
    put_u32_at(record, 8, text_length);
    put_u32_at(record, 12, empty_at);
    put_u32_at(record, 20, empty_at);
    put_u32_at(record, 28, font_at);
    put_u32_at(record, 32, font_count);
    put_f32_at(record, 36, size);
    put_f32_at(record, 44, NAN);
    put_f32_at(record, 48, NAN);
    record[43] = 1;
    record[56] = 8;
    put_u32_at(record, 60, 3);
    put_f32_at(record, 64, MEASURE_WIDTH);
}

/* A MeasureNode record: any width up to MEASURE_WIDTH, any height. */
static void node_request(uint8_t *record, uint32_t node_id) {
    record[0] = 2;
    put_u32_at(record, 4, node_id);
    put_f32_at(record, 8, 0.0f);
    put_f32_at(record, 12, MEASURE_WIDTH);
    put_f32_at(record, 16, 0.0f);
    put_f32_at(record, 20, INFINITY);
}

static int same(const MeasureResult *text, const MeasureResult *node) {
    return text->status == 0 && node->status == 0 && text->width > 0 && text->height > 0 &&
           memcmp(&text->width, &node->width, sizeof(float)) == 0 &&
           memcmp(&text->height, &node->height, sizeof(float)) == 0;
}

static void measure_round_trip(void) {
    enum { COUNT = 4 };
    uint8_t requests[COUNT * MEASURE_RECORD + sizeof measured_label + sizeof HTML_TEXT + 12];
    memset(requests, 0, sizeof requests);
    uint32_t at = COUNT * MEASURE_RECORD;
    uint32_t label_at = at, label_length = (uint32_t)strlen(measured_label);
    memcpy(requests + label_at, measured_label, label_length);
    at += label_length;
    uint32_t html_at = at, html_length = (uint32_t)strlen(HTML_TEXT);
    memcpy(requests + html_at, HTML_TEXT, html_length);
    at += html_length;
    /* The font list as a measure request carries it: the records alone, no count. */
    uint32_t font_at = at;
    memcpy(requests + font_at, html_font + 4, 12);
    at += 12;
    /* Body is tag 5; a role of 0 is text with no role. */
    text_request(requests, label_at, label_length, 5, 0.0f, at, 0, at);
    node_request(requests + MEASURE_RECORD, LABEL_NODE);
    text_request(requests + 2 * MEASURE_RECORD, html_at, html_length, 0, 16.0f, font_at, 1, at);
    node_request(requests + 3 * MEASURE_RECORD, HTML_NODE);

    MeasureResult results[COUNT];
    memset(results, 0, sizeof results);
    int32_t status = compose_rust_renderer_measure(requests, at, COUNT, results);
    for (int index = 0; index < COUNT; index++) {
        printf("compose_rust_renderer_measure: status %d, record %d: %u %.3fx%.3f\n", status,
               index, results[index].status, results[index].width, results[index].height);
    }
    int agree = status == 0 && same(&results[0], &results[1]) && same(&results[2], &results[3]);
    printf("compose_rust_renderer_measure: %s\n", agree ? "agree" : "disagree");
    fflush(stdout);
}

/* A monotonic clock in nanoseconds, for timing the sidebar below. */
static uint64_t now_ns(void) {
#ifdef _WIN32
    LARGE_INTEGER counter, frequency;
    QueryPerformanceCounter(&counter);
    QueryPerformanceFrequency(&frequency);
    return (uint64_t)((double)counter.QuadPart * 1e9 / (double)frequency.QuadPart);
#else
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (uint64_t)time.tv_sec * 1000000000ull + (uint64_t)time.tv_nsec;
#endif
}

/*
 * One sidebar's worth of measuring through the real boundary, timed: two hundred labels,
 * each asked for its narrowest width, its widest and its height at 180, which is six
 * hundred measurements in one call. The first call is the cold one; the median of the
 * rest says what a layout that asks every frame pays.
 */
#define SIDEBAR_LABELS 200
#define SIDEBAR_COUNT (3 * SIDEBAR_LABELS)
#define SIDEBAR_ROUNDS 7
static uint8_t sidebar_requests[SIDEBAR_COUNT * MEASURE_RECORD + SIDEBAR_LABELS * 48 + 16];
static MeasureResult sidebar_results[SIDEBAR_COUNT];

static int compare_u64(const void *left, const void *right) {
    uint64_t a = *(const uint64_t *)left, b = *(const uint64_t *)right;
    return a < b ? -1 : a > b;
}

static void measure_sidebar(void) {
    memset(sidebar_requests, 0, sizeof sidebar_requests);
    uint32_t at = SIDEBAR_COUNT * MEASURE_RECORD;
    uint32_t font_at = at;
    memcpy(sidebar_requests + font_at, html_font + 4, 12);
    at += 12;
    for (int label = 0; label < SIDEBAR_LABELS; label++) {
        char text[48];
        int length = snprintf(text, sizeof text, "Sidebar entry %d: a label of a few words", label + 1);
        memcpy(sidebar_requests + at, text, (size_t)length);
        for (int ask = 0; ask < 3; ask++) {
            uint8_t *record = sidebar_requests + (uint32_t)(label * 3 + ask) * MEASURE_RECORD;
            text_request(record, at, (uint32_t)length, 0, 13.0f, font_at, 1, font_at);
            /* Min content, max content, then at most 180. */
            put_u32_at(record, 60, (uint32_t)(ask + 1));
            put_f32_at(record, 64, 180.0f);
        }
        at += (uint32_t)length;
    }
    uint64_t times[SIDEBAR_ROUNDS];
    int32_t status = 0;
    for (int round = 0; round < SIDEBAR_ROUNDS; round++) {
        uint64_t started = now_ns();
        status |= compose_rust_renderer_measure(sidebar_requests, at, SIDEBAR_COUNT, sidebar_results);
        times[round] = now_ns() - started;
    }
    uint64_t first = times[0];
    qsort(times + 1, SIDEBAR_ROUNDS - 1, sizeof times[0], compare_u64);
    printf("measure_sidebar: %d measurements through the boundary, status %d: %.3f ms cold, "
           "%.3f ms median of %d\n",
           SIDEBAR_COUNT, status, (double)first / 1e6, (double)times[SIDEBAR_ROUNDS / 2] / 1e6,
           SIDEBAR_ROUNDS - 1);
    fflush(stdout);
}

HOST_EXPORT int32_t compose_rust_host_init(
    const uint8_t *handshake, uint32_t len, MutationBatch *out
) {
    if (out == NULL || handshake == NULL || len < 12) {
        return STATUS_PROTOCOL_ERROR;
    }
    /* A frame after the tree is applied, which is where the measure round trip is made. */
    compose_rust_renderer_request_frame();
    build_tree("smoke host: 0 clicks");
    out->ptr = batch_bytes;
    out->len = batch_length;
    out->result = 0;
    printf("compose_rust_host_init: %u bytes, %u records\n", batch_length, record_count);
    return STATUS_OK;
}

HOST_EXPORT int32_t compose_rust_host_dispatch_event(
    const uint8_t *event, uint32_t len, MutationBatch *out
) {
    static int clicks;
    static char label[64];
    if (out == NULL || event == NULL || len < 4) {
        return STATUS_PROTOCOL_ERROR;
    }
    clicks += 1;
    snprintf(label, sizeof label, "smoke host: %d clicks", clicks);
    build_label_update(label);
    printf("compose_rust_host_dispatch_event: click %d\n", clicks);
    out->ptr = batch_bytes;
    out->len = batch_length;
    out->result = 1;
    return STATUS_OK;
}

HOST_EXPORT int32_t compose_rust_host_render_frame(
    uint64_t frame_time_nanos, MutationBatch *out
) {
    (void)frame_time_nanos;
    if (out == NULL) {
        return STATUS_PROTOCOL_ERROR;
    }
    frames_served += 1;
    if (frames_served == 1) {
        build_html_text();
        out->ptr = batch_bytes;
        out->len = batch_length;
        out->result = 0;
        /* One more frame, with the new text applied, for the measure round trip. */
        compose_rust_renderer_request_frame();
        return STATUS_OK;
    }
    if (frames_served == 2) {
        measure_round_trip();
        measure_sidebar();
    }
    out->ptr = NULL;
    out->len = 0;
    out->result = 0;
    return STATUS_OK;
}

HOST_EXPORT void compose_rust_host_release_batch(MutationBatch *batch) {
    if (batch != NULL) {
        batch->ptr = NULL;
        batch->len = 0;
        batch->result = 0;
    }
}

HOST_EXPORT void compose_rust_host_shutdown(void) {
    printf("compose_rust_host_shutdown\n");
}

int main(void) {
    int32_t status = compose_rust_renderer_run();
    printf("compose_rust_renderer_run returned %d\n", status);
    return status == 0 ? 0 : 1;
}
