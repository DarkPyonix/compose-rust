/*
 * What the C library will say about reading dates and times in this locale.
 *
 * A header of ours that includes the system's, rather than the system's directly. Naming
 * `langinfo.h` in the definition binds nothing at all: the toolchain takes every header in
 * its own sysroot to be already answered by the posix platform library and drops the lot,
 * so the definition builds, the library is written, and it is empty. A header on the
 * definition's own include path is not one of those and is bound.
 *
 * Nothing is written out by hand here. The values are the ones glibc computes, read
 * through its own header at the moment this is compiled, because `ABDAY_1` and the rest
 * are not small integers: they are a locale category shifted into place, and a number
 * copied here would be right until it was not.
 */
#ifndef DXC_LANGINFO_H
#define DXC_LANGINFO_H

#include <langinfo.h>

/* Which day a week starts on is a GNU extension and every locale has an answer for it.
   POSIX offers no way to ask, so where it is absent the caller is told nothing and picks
   its own first day. */
#ifdef _NL_TIME_FIRST_WEEKDAY
static const int dxc_langinfo_first_weekday = _NL_TIME_FIRST_WEEKDAY;
#else
static const int dxc_langinfo_first_weekday = 0;
#endif

static const int dxc_langinfo_abbreviated_day = ABDAY_1;
static const int dxc_langinfo_month = MON_1;
static const int dxc_langinfo_time_format = T_FMT;

/* The call itself. Wrapped rather than named directly for the same reason the constants
   are: what is in the system's header is dropped, and what is in this one is kept. */
static inline const char *dxc_langinfo(int item) {
    return nl_langinfo((nl_item)item);
}

#endif
