package dev.darkpyonix.composerust.ui.platform

import dxc_host.dxc_linked_host_function
import kotlinx.cinterop.COpaquePointer

/**
 * One of the Host's functions, as the link resolved it, or null.
 *
 * On Linux the renderer is a static archive inside the application, so the Host's
 * functions are in the same executable and `cinterop/host.def` refers to them by name.
 * That reference is what makes an application a single file: looking the name up at run
 * time instead reads the executable's dynamic symbol table, and a Rust executable puts
 * nothing there unless a shared library beside it asks for it.
 *
 * The references are weak, so this is null where no Host was linked, which is the
 * renderer module's own test executable. The caller then looks the name up.
 */
internal fun linkedHostFunction(name: String): COpaquePointer? = dxc_linked_host_function(name)
