// What the Kotlin/Native object asks of MinGW's own runtime, answered by the MSVC one.
//
// The Windows renderer is a MinGW object inside an MSVC executable. Linking MinGW's extras
// library whole brings its own strtof, stat and the rest, which collide with the static
// UCRT, so it is not linked. These are the only things the renderer's object and the GCC
// runtime it carries actually reach for from it.
#include <process.h>
#include <stdarg.h>
#include <stdio.h>
#include <windows.h>

// MinGW's C99-conforming vsnprintf. The UCRT's own already conforms.
int __mingw_vsnprintf(char *buffer, size_t size, const char *format, va_list arguments) {
    return vsnprintf(buffer, size, format, arguments);
}

// The Kotlin runtime's condition variable waits are timed with it. Wall-clock time to the
// microsecond, as POSIX defines it: seconds and microseconds since 1970. `struct timeval`
// is the one windows.h already declares through winsock.

int gettimeofday(struct timeval *now, void *zone) {
    (void)zone;
    FILETIME file_time;
    GetSystemTimePreciseAsFileTime(&file_time);
    // Hundreds of nanoseconds since 1601, moved to 1970.
    unsigned long long ticks = ((unsigned long long)file_time.dwHighDateTime << 32) | file_time.dwLowDateTime;
    ticks -= 116444736000000000ULL;
    now->tv_sec = (long)(ticks / 10000000ULL);
    now->tv_usec = (long)((ticks % 10000000ULL) / 10ULL);
    return 0;
}

unsigned int sleep(unsigned int seconds) {
    Sleep(seconds * 1000);
    return 0;
}

// MinGW's startup code defines this crash filter, and winpthread names it in the unwind
// data of the function that starts a thread. None of MinGW's startup code is linked, so a
// crash on a Kotlin thread is answered "not handled" and reaches ordinary Windows handling
// rather than MinGW's translation of it into a signal.
long _gnu_exception_handler(void *exception) {
    (void)exception;
    return 0;
}

// winpthread was built against the DLL runtime and starts threads through import pointers.
// The static runtime has the functions and not the pointers, so the pointers are made here.
uintptr_t (__cdecl *__imp__beginthreadex)(void *, unsigned, _beginthreadex_proc_type, void *,
                                         unsigned, unsigned *) = _beginthreadex;
void (__cdecl *__imp__endthreadex)(unsigned) = _endthreadex;

// libgcc's CPU feature probe. Kotlin/Native reads the table it fills, and libgcc registers
// it as a prioritised MinGW constructor in a section the MSVC runtime never runs, so it is
// put in the MSVC runtime's own initialiser table instead.
int __cpu_indicator_init(void);
static void init_cpu_model(void) { __cpu_indicator_init(); }
#pragma section(".CRT$XCU", read)
__declspec(allocate(".CRT$XCU")) static void (*const init_cpu_model_entry)(void) = init_cpu_model;
