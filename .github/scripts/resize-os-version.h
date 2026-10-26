/*
 * The Windows version a resize result was measured on, as one line: the kernel's own version
 * (RtlGetVersion, which no compatibility shim rewrites, unlike GetVersionEx) and, from
 * HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion, the update build revision (UBR), the
 * feature update's name (DisplayVersion, "24H2") and the product name.
 *
 * Whether a window's frame waits for its content during a live resize changes between builds
 * of Windows, so a resize result means nothing without the full build it was taken on:
 * 10.0.26100.6584, not "Windows 11".
 *
 * Header only, shared by resize-probe.c and resize-control.c. Needs advapi32.lib.
 */
#ifndef RESIZE_OS_VERSION_H
#define RESIZE_OS_VERSION_H

#include <windows.h>
#include <stdio.h>
#include <string.h>

#pragma comment(lib, "advapi32.lib")

typedef LONG(WINAPI *resize_rtl_get_version_fn)(OSVERSIONINFOW *);

static void resize_registry_string(const wchar_t *name, char *into, size_t size) {
    wchar_t value[128];
    DWORD bytes = sizeof value;
    into[0] = 0;
    if (RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", name,
                     RRF_RT_REG_SZ, NULL, value, &bytes) == ERROR_SUCCESS) {
        WideCharToMultiByte(CP_UTF8, 0, value, -1, into, (int)size, NULL, NULL);
    } else {
        snprintf(into, size, "unknown");
    }
}

/*
 * Writes "windows 10.0.<build>.<ubr> (build <build>, ubr <ubr>, display_version <...>,
 * product \"<...>\", installation_type <...>)" into line.
 */
static void resize_os_version(char *line, size_t size) {
    OSVERSIONINFOW version;
    memset(&version, 0, sizeof version);
    version.dwOSVersionInfoSize = sizeof version;
    resize_rtl_get_version_fn get_version =
        (resize_rtl_get_version_fn)(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion");
    if (get_version == NULL || get_version(&version) != 0) {
        version.dwMajorVersion = version.dwMinorVersion = version.dwBuildNumber = 0;
    }
    DWORD ubr = 0;
    DWORD bytes = sizeof ubr;
    int have_ubr = RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", L"UBR",
                                RRF_RT_REG_DWORD, NULL, &ubr, &bytes) == ERROR_SUCCESS;
    char display_version[64], product[128], installation[64];
    resize_registry_string(L"DisplayVersion", display_version, sizeof display_version);
    resize_registry_string(L"ProductName", product, sizeof product);
    resize_registry_string(L"InstallationType", installation, sizeof installation);
    char revision[24];
    if (have_ubr) {
        snprintf(revision, sizeof revision, "%lu", (unsigned long)ubr);
    } else {
        snprintf(revision, sizeof revision, "unknown");
    }
    snprintf(line, size,
             "windows %lu.%lu.%lu.%s (build %lu, ubr %s, display_version %s, product \"%s\", installation_type %s)",
             (unsigned long)version.dwMajorVersion, (unsigned long)version.dwMinorVersion,
             (unsigned long)version.dwBuildNumber, revision, (unsigned long)version.dwBuildNumber, revision,
             display_version, product, installation);
}

#endif
