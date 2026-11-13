package dev.darkpyonix.composerust.tooling

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost

/**
 * JVM development shell for the interpreter.
 *
 * The screen itself is [m0DemoHost], which the web development shell renders too: one
 * scripted Host, so a change to the demo screen is seen on both without building Rust.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ComposeRust dev") {
        val connection = remember { m0DemoHost() }
        ComposeRustContent(rememberComposeRustHost(connection))
    }
}
