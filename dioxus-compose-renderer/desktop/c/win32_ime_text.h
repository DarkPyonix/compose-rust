#ifndef DXC_WIN32_IME_TEXT_H
#define DXC_WIN32_IME_TEXT_H

#include <stddef.h>
#include <stdint.h>

// Encode complete UTF-16 scalar values only. Keep one byte for the terminator.
static size_t dxc_utf16_to_utf8(const uint16_t *source, size_t units,
                                char *target, size_t capacity) {
    size_t written = 0;
    for (size_t index = 0; index < units; index++) {
        uint32_t scalar = source[index];
        if (scalar >= 0xd800 && scalar <= 0xdbff && index + 1 < units &&
            source[index + 1] >= 0xdc00 && source[index + 1] <= 0xdfff) {
            scalar = 0x10000 + ((scalar - 0xd800) << 10) +
                     (source[++index] - 0xdc00);
        } else if (scalar >= 0xd800 && scalar <= 0xdfff) {
            scalar = 0xfffd;
        }
        size_t bytes = scalar < 0x80 ? 1 : scalar < 0x800 ? 2 : scalar < 0x10000 ? 3 : 4;
        if (written + bytes >= capacity) break;
        if (bytes == 1) {
            target[written++] = (char)scalar;
        } else {
            for (size_t part = bytes - 1; part > 0; part--) {
                target[written + part] = (char)(0x80 | (scalar & 0x3f));
                scalar >>= 6;
            }
            target[written] = (char)((0xf0 << (4 - bytes)) | scalar);
            written += bytes;
        }
    }
    if (capacity > 0) target[written] = '\0';
    return written;
}

#endif
