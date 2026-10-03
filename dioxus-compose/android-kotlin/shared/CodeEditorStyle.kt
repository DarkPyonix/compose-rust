package dioxus.compose.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Severity
import dioxus.compose.foundation.code.UnderlineShape

/**
 * Everything a code editor needs from the design system, answered in one call.
 *
 * The Host sends text, colour runs as roles, and decorations as ranges. The gutter, the line
 * numbers, how the current line is shown, the shape and weight of an underline, how faint a
 * suggestion is and how far a tab advances are all this, and none of them can be asked for
 * from the other side.
 */
data class CodeEditorStyle(
    /** What the text sits on. */
    val container: Color,
    /** The ink of text no colour run covers. */
    val text: Color,
    /** What the line numbers sit on. */
    val gutter: Color,
    val lineNumber: Color,
    /** The number of the line the caret is on. */
    val currentLineNumber: Color,
    /** A fill behind the line the caret is on, transparent where the system draws none. */
    val currentLine: Color,
    /** A frame around the line the caret is on, transparent where the system draws none. */
    val currentLineBorder: Color,
    /** A rule between the gutter and the text, transparent where the system draws none. */
    val gutterDivider: Color,
    val gutterDividerWidth: Dp,
    /** Room either side of the line numbers. */
    val gutterPadding: Dp,
    /** Room between the gutter and the first column of text. */
    val textInset: Dp,
    val selection: Color,
    val cursor: Color,
    val underline: UnderlineShape,
    /** How a hint is marked. Most systems mark one more quietly than a problem. */
    val hintUnderline: UnderlineShape,
    val underlineWidth: Dp,
    /** The role an underline takes where the Host did not name one. */
    val severityRoles: Map<Severity, ColorRole>,
    /** How much of the text's ink a suggestion shown in place is drawn with. */
    val ghostTextAlpha: Float,
    /** The ink of a lens above a line. */
    val lens: Color,
    /** How many columns a tab advances where the Host did not say. */
    val tabWidth: Int,
    /** How long a pointer rests over the text before the Host is told. */
    val hoverDelayMillis: Long,
) {
    /** The colour an underline of this severity is drawn in, given the role the Host named. */
    fun underlineColor(severity: Severity?, named: ColorRole?, theme: ResolvedTheme): Color =
        theme.color(named ?: severityRoles[severity ?: Severity.Information] ?: ColorRole.Error)

    /** The shape of an underline of this severity. */
    fun underlineShape(severity: Severity?): UnderlineShape =
        if (severity == Severity.Hint) hintUnderline else underline
}

/**
 * The editor every system starts from, read from its own token table: its surface, its quiet
 * ink for the numbers, its accent for the caret and the selection, its error role for errors.
 *
 * A system that says nothing gets an editor in its own colours rather than another system's.
 */
fun defaultCodeEditorStyle(theme: ResolvedTheme): CodeEditorStyle = CodeEditorStyle(
    container = theme.color(ColorRole.Surface),
    text = theme.color(ColorRole.OnSurface),
    gutter = theme.color(ColorRole.Surface),
    lineNumber = theme.color(ColorRole.OnSurfaceVariant),
    currentLineNumber = theme.color(ColorRole.OnSurface),
    currentLine = theme.color(ColorRole.SurfaceVariant).copy(alpha = 0.5f),
    currentLineBorder = Color.Transparent,
    gutterDivider = Color.Transparent,
    gutterDividerWidth = 0.dp,
    gutterPadding = 12.dp,
    textInset = 8.dp,
    selection = theme.color(ColorRole.Primary).copy(alpha = 0.3f),
    cursor = theme.color(ColorRole.Primary),
    underline = UnderlineShape.Wavy,
    hintUnderline = UnderlineShape.Dotted,
    underlineWidth = 1.dp,
    severityRoles = mapOf(
        Severity.Error to ColorRole.Error,
        Severity.Warning to ColorRole.Tertiary,
        Severity.Information to ColorRole.Primary,
        Severity.Hint to ColorRole.OnSurfaceVariant,
    ),
    ghostTextAlpha = 0.5f,
    lens = theme.color(ColorRole.OnSurfaceVariant),
    tabWidth = 4,
    hoverDelayMillis = theme.rules.motion.tooltipDelayMillis.toLong(),
)
