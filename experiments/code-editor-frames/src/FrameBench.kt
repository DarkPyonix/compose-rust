package dioxus.compose.bench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dioxus.compose.foundation.code.CodeEditorCallbacks
import dioxus.compose.foundation.code.CodeEditorLook
import dioxus.compose.foundation.code.CodeEditorModel
import dioxus.compose.foundation.code.CodeEditorPath
import dioxus.compose.foundation.code.CodeEditorSurface
import dioxus.compose.foundation.code.UnderlineShape
import dioxus.compose.foundation.code.installDrawnCodeEditor
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.DecorationKind
import dioxus.compose.protocol.DecorationRecord
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.Severity
import dioxus.compose.protocol.SyntaxSpanRecord
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.SwingUtilities

/**
 * Frame times for a code editor holding a hundred thousand lines, along each path the
 * editor can draw with, and for plain Compose doing the nearest thing it can on its own.
 *
 * Each run opens a real window, waits for it to settle, and then drives it with real AWT
 * events, one per frame: wheel scrolling, then typing. Vertical sync is off, so the time
 * between two frames is the time a frame took rather than the display's refresh interval.
 *
 * Usage: `FrameBench [windowed] [whole] [drawn] [baseline] [--lines=100000] [--frames=600]`.
 * With no path named, all of them run. Results are printed and written to `results/`.
 */
fun main(args: Array<String>) {
    System.setProperty("skiko.vsync.enabled", "false")
    installDrawnCodeEditor()
    val lines = args.firstOrNull { it.startsWith("--lines=") }?.substringAfter('=')?.toInt() ?: 100_000
    val frames = args.firstOrNull { it.startsWith("--frames=") }?.substringAfter('=')?.toInt() ?: 600
    val named = args.filter { !it.startsWith("--") }.map { it.lowercase() }
    val runs = listOf("windowed", "whole", "drawn", "baseline").filter { named.isEmpty() || it in named }
    val document = sourceText(lines)
    val results = runs.map { name ->
        val result = measure(name, document, lines, frames)
        println(result.toJson())
        result
    }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val directory = File("results").apply { mkdirs() }
    val file = File(directory, "frames-$stamp.json")
    file.writeText(
        buildString {
            append("{\n  \"lines\": $lines,\n  \"framesPerPhase\": $frames,\n")
            append("  \"os\": \"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}\",\n")
            append("  \"java\": \"${System.getProperty("java.version")}\",\n")
            append("  \"runs\": [\n")
            append(results.joinToString(",\n") { "    " + it.toJson() })
            append("\n  ]\n}\n")
        },
    )
    println("written to ${file.absolutePath}")
}

/** Rust-like source, with comments, strings, tabs and an emoji every so often. */
private fun sourceText(lines: Int): String = buildString {
    for (line in 0 until lines) {
        when (line % 10) {
            0 -> append("// block ").append(line / 10).append(": what the next lines do")
            1 -> append("fn step_").append(line).append("(input: &str) -> Result<usize, Error> {")
            2 -> append("    let parsed = input.trim().parse::<usize>()?;")
            3 -> append("\tlet label = \"line ").append(line).append(" 😀\";")
            4 -> append("    if parsed > ").append(line).append(" { return Err(Error::TooLarge); }")
            5 -> append("    println!(\"{label}: {parsed}\");")
            6 -> append("    Ok(parsed * 2 + ").append(line % 7).append(")")
            7 -> append("}")
            8 -> append("")
            else -> append("#[test] fn step_").append(line - 8).append("_works() { assert!(step_").append(line - 8).append("(\"1\").is_ok()); }")
        }
        append('\n')
    }
}

/** Colour runs for keywords and strings, and a diagnostic every hundred lines, as an application would send them. */
private fun overlays(model: CodeEditorModel) {
    val spans = ArrayList<SyntaxSpanRecord>()
    val decorations = ArrayList<DecorationRecord>()
    val document = model.document
    for (line in 0 until document.lineCount) {
        val text = document.line(line)
        for (keyword in listOf("fn ", "let ", "if ", "return ")) {
            var at = text.indexOf(keyword)
            while (at >= 0) {
                spans += SyntaxSpanRecord(0, line, at, line, at + keyword.length - 1, Paint.Role(ColorRole.Primary))
                at = text.indexOf(keyword, at + 1)
            }
        }
        val quote = text.indexOf('"')
        val close = text.lastIndexOf('"')
        if (quote >= 0 && close > quote) {
            spans += SyntaxSpanRecord(0, line, quote, line, close + 1, Paint.Role(ColorRole.Tertiary))
        }
        if (text.startsWith("//")) spans += SyntaxSpanRecord(0, line, 0, line, text.length, Paint.Role(ColorRole.Secondary))
        if (line % 100 == 2 && text.length > 12) {
            decorations += DecorationRecord(0, DecorationKind.Underline, Severity.Warning, null, line, 8, line, 14, 0L, "")
        }
    }
    model.setSyntaxSpans(spans)
    model.setDecorations(decorations)
    model.drainOutput()
}

private val look = CodeEditorLook(
    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF1F2328)),
    container = Color.White,
    gutter = Color(0xFFF6F8FA),
    lineNumber = Color(0xFF8C959F),
    currentLineNumber = Color(0xFF1F2328),
    currentLine = Color(0x14000000),
    currentLineBorder = Color.Transparent,
    gutterDivider = Color.Transparent,
    gutterDividerWidth = 0.dp,
    gutterPadding = 12.dp,
    textInset = 8.dp,
    selection = Color(0x4D0969DA),
    cursor = Color(0xFF0969DA),
    underlineWidth = 1.dp,
    underline = { Color(0xFFBF8700) to UnderlineShape.Wavy },
    paint = { paint ->
        when ((paint as? Paint.Role)?.role) {
            ColorRole.Primary -> Color(0xFFCF222E)
            ColorRole.Tertiary -> Color(0xFF0A3069)
            ColorRole.Secondary -> Color(0xFF6E7781)
            else -> Color(0xFF1F2328)
        }
    },
    ghostTextAlpha = 0.5f,
    lens = Color(0xFF8C959F),
    tabWidth = 4,
    hoverDelayMillis = 500,
    commandIsMeta = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
)

/** Frame intervals for each phase of one run, in nanoseconds. */
private class RunResult(
    val name: String,
    val openMillis: Double,
    val phases: Map<String, LongArray>,
    val changes: Int,
) {
    fun toJson(): String = buildString {
        append("{\"path\": \"$name\", \"openMillis\": ${"%.1f".format(openMillis)}, \"changesReported\": $changes")
        for ((phase, samples) in phases) {
            val sorted = samples.sorted()
            fun ms(nanos: Long) = "%.2f".format(nanos / 1_000_000.0)
            fun at(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
            val over = samples.count { it > 16_666_667L }
            append(", \"$phase\": {\"frames\": ${samples.size}")
            if (samples.isNotEmpty()) {
                append(", \"p50Ms\": ${ms(at(0.50))}, \"p95Ms\": ${ms(at(0.95))}, \"p99Ms\": ${ms(at(0.99))}")
                append(", \"maxMs\": ${ms(sorted.last())}, \"meanMs\": ${ms(samples.average().toLong())}")
                append(", \"over16ms\": $over")
            }
            append("}")
        }
        append("}")
    }
}

private fun measure(name: String, document: String, lines: Int, frames: Int): RunResult {
    var result: RunResult? = null
    val started = System.nanoTime()
    application(exitProcessOnExit = false) {
        val windowState = rememberWindowState(size = DpSize(1100.dp, 800.dp))
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "frames: $name") {
            val composeWindow = window
            var changes = 0
            val model = remember {
                CodeEditorModel(document).also(::overlays)
            }
            val callbacks = remember {
                CodeEditorCallbacks(
                    onOutput = { changes += model.drainOutput().size },
                    onSave = {},
                    onHover = { _, _, _ -> },
                )
            }
            when (name) {
                "windowed" -> CodeEditorSurface(model, look, callbacks, Modifier.fillMaxSize(), CodeEditorPath.Windowed)
                "whole" -> CodeEditorSurface(model, look, callbacks, Modifier.fillMaxSize(), CodeEditorPath.Whole)
                "drawn" -> CodeEditorSurface(model, look, callbacks, Modifier.fillMaxSize(), CodeEditorPath.Drawn)
                else -> Baseline(document, lines)
            }
            LaunchedEffect(Unit) {
                var opened = 0L
                withFrameNanos { opened = System.nanoTime() }
                // Settle: fonts, caches and the first refill.
                repeat(60) { withFrameNanos { } }
                val phases = LinkedHashMap<String, LongArray>()
                phases["idle"] = record(frames / 4) { }
                click(composeWindow, 160, 120)
                repeat(10) { withFrameNanos { } }
                phases["scroll"] = record(frames) { wheel(composeWindow, 3) }
                phases["scrollBack"] = record(frames / 2) { wheel(composeWindow, -6) }
                click(composeWindow, 260, 200)
                repeat(10) { withFrameNanos { } }
                var typed = 0
                phases["typing"] = record(frames) {
                    typed += 1
                    if (typed % 40 == 0) key(composeWindow, KeyEvent.VK_ENTER, '\n') else key(composeWindow, KeyEvent.VK_A, 'a')
                }
                result = RunResult(name, (opened - started) / 1_000_000.0, phases, changes)
                exitApplication()
            }
        }
    }
    return result ?: error("the $name run ended without a result")
}

/** Plain Compose's nearest equivalents: a lazy list of every line, and a small field to type in. */
@Composable
private fun Baseline(document: String, lines: Int) {
    val all = remember { document.split('\n') }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxSize()) {
            items(lines) { index -> BasicText(all[index], style = look.textStyle) }
        }
        val field = remember { TextFieldState(all.take(60).joinToString("\n")) }
        BasicTextField(field, Modifier.weight(1f).fillMaxSize(), textStyle = look.textStyle)
    }
}

/** One interval per frame for [count] frames, doing [each] once per frame first. */
private suspend fun record(count: Int, each: () -> Unit): LongArray {
    val samples = LongArray(count)
    var previous = withFrameNanos { it }
    for (index in 0 until count) {
        each()
        val now = withFrameNanos { it }
        samples[index] = now - previous
        previous = now
    }
    return samples
}

private fun target(window: ComposeWindow, x: Int, y: Int): Component =
    SwingUtilities.getDeepestComponentAt(window.contentPane, x, y) ?: window

private fun post(event: java.awt.AWTEvent) = Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)

private fun click(window: ComposeWindow, x: Int, y: Int) {
    val component = target(window, x, y)
    val now = System.currentTimeMillis()
    post(MouseEvent(component, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
    post(MouseEvent(component, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1))
    post(MouseEvent(component, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON1))
}

private fun wheel(window: ComposeWindow, notches: Int) {
    val component = target(window, 400, 400)
    post(
        MouseWheelEvent(
            component, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 400, 400, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches,
        ),
    )
}

private fun key(window: ComposeWindow, code: Int, character: Char) {
    val component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: target(window, 400, 400)
    val now = System.currentTimeMillis()
    post(KeyEvent(component, KeyEvent.KEY_PRESSED, now, 0, code, character))
    post(KeyEvent(component, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, character))
    post(KeyEvent(component, KeyEvent.KEY_RELEASED, now, 0, code, character))
}
