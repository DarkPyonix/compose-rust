#include <assert.h>
#include <string.h>
#include "../../dioxus-compose-renderer/desktop/c/win32_ime_text.h"

static void expect(const uint16_t *source, size_t units, size_t capacity,
                   const char *expected) {
    char text[96];
    memset(text, 0x7f, sizeof text);
    size_t written = dxc_utf16_to_utf8(source, units, text, capacity);
    assert(written == strlen(expected));
    assert(strcmp(text, expected) == 0);
}

int main(void) {
    const uint16_t languages[] = { 0xd55c, 0x3042, 0x4e2d, 0x56fd };
    expect(languages, 4, 96, "한あ中国");

    const uint16_t supplementary[] = { 0xd83d, 0xde00, 'A' };
    expect(supplementary, 3, 6, "😀A");
    expect(supplementary, 3, 5, "😀");
    expect(supplementary, 3, 4, "");

    const uint16_t long_text[] = { 'A', 0xd55c, 0xd83d, 0xde00, 'B' };
    expect(long_text, 5, 5, "A한");
    expect(long_text, 5, 4, "A");

    const uint16_t invalid[] = { 0xd800, 'x', 0xdc00 };
    expect(invalid, 3, 96, "�x�");
    expect(NULL, 0, 96, "");
    return 0;
}
