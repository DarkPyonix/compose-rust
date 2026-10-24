/*
 * Connecting to a Unix domain socket by path or by abstract name.
 *
 * Kotlin/Native's posix platform library for Linux has `socket` and `connect` and no
 * `struct sockaddr_un`, so the address is put together here, through the C library's own
 * header, and the Kotlin side passes the name and gets connect's answer back. Written out
 * by hand in Kotlin instead, the structure would be a size and an offset copied from
 * memory, which is right until a C library lays it out differently.
 *
 * A header of ours that includes the system's, for the reason dxc_langinfo.h gives: a
 * header in the toolchain's own sysroot is taken to be answered by the platform library
 * already and is dropped from the binding, and one on the definition's own include path is
 * kept.
 */
#ifndef DXC_UNIX_SOCKET_H
#define DXC_UNIX_SOCKET_H

#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>

/* Connects `fd` to the socket called `name` (`length` bytes, not terminated). An abstract
   name starts with a zero byte and is not terminated; a path is terminated and does not
   start with one. Returns connect's result, or -1 when the name does not fit. */
static inline int dxc_connect_unix(int fd, const void *name, int length, int abstract_name) {
    struct sockaddr_un address;
    int start = abstract_name ? 1 : 0;
    if (length < 0 || (size_t)(start + length + 1) > sizeof address.sun_path) {
        return -1;
    }
    memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + start, name, (size_t)length);
    socklen_t size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + (size_t)start +
                                 (size_t)length + (abstract_name ? 0u : 1u));
    return connect(fd, (const struct sockaddr *)&address, size);
}

#endif
