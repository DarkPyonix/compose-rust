package dioxus.compose.tooling

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost

/**
 * JVM development shell for the interpreter.
 *
 * The screen itself is [m0DemoHost], which the web development shell renders too: one
 * scripted Host, so a change to the demo screen is seen on both without building Rust.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "DioxusCompose dev") {
        val connection = remember { m0DemoHost() }
        DioxusContent(rememberDioxusHost(connection))
    }
}
