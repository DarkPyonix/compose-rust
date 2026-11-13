// The part of the MinGW bridge that only an application linking the UCRT statically needs.
//
// winpthread, in the GCC runtime the Kotlin/Native object carries, was built against the DLL
// runtime and starts and ends threads through import pointers, `__imp__beginthreadex`,
// `__imp__endthreadex` and `__imp_longjmp`. Where the application imports the UCRT from Windows, which is the
// default, the import library has those pointers and this file is not linked: defining them a
// second time would fail the link. Where the application asked for `+crt-static`, the static
// runtime has the functions and not the pointers, so the pointers are made here, and the Host's
// build script links this library only then.
#include <process.h>
#include <setjmp.h>
#include <stdint.h>

uintptr_t (__cdecl *__imp__beginthreadex)(void *, unsigned, _beginthreadex_proc_type, void *,
                                         unsigned, unsigned *) = _beginthreadex;
void (__cdecl *__imp__endthreadex)(unsigned) = _endthreadex;
// pthread_exit leaves a thread with longjmp, through the same kind of pointer.
void (__cdecl *__imp_longjmp)(jmp_buf, int) = longjmp;
