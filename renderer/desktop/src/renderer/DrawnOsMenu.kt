package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.LocalDesignTheme
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.protocol.TypeRole

/** Test tag of the root popup drawn by [DrawnOsMenuPresenter]. */
const val DRAWN_OS_MENU_POPUP_TAG = "dxc-drawn-os-menu"

/** Test tag of a drawn menu popup at [prefix] (empty for the root menu, submenu indices otherwise). */
fun drawnOsMenuPopupTestTag(prefix: List<Int> = emptyList()): String =
    if (prefix.isEmpty()) DRAWN_OS_MENU_POPUP_TAG
    else "$DRAWN_OS_MENU_POPUP_TAG-${prefix.joinToString("-")}"

/** Test tag of a single entry row at [path] in a drawn menu. */
fun drawnOsMenuEntryTestTag(path: List<Int>): String =
    "$DRAWN_OS_MENU_POPUP_TAG-entry-${path.joinToString("-")}"

/**
 * Draws entry menus in Compose using the active design system, for backends without an OS popup
 * menu service (Linux X11 windows, web, and tests).
 *
 * Implements [OsMenuPresenter] over the same normalized [OsMenuEntry] list, path resolution, and
 * single-delivery [OsMenuResultOnce] contract as platform OS menu presenters, so drawn menus show
 * the exact same entries, order, separators, submenus, and enabled state.
 */
class DrawnOsMenuPresenter : OsMenuPresenter {

    internal class ActiveMenu(
        val request: OsMenuRequest,
        val entries: List<OsMenuEntry>,
        val once: OsMenuResultOnce,
    ) {
        var openSubmenuPath by mutableStateOf<List<Int>>(emptyList())
    }

    internal var active by mutableStateOf<ActiveMenu?>(null)
        private set

    /**
     * Latest resolved theme synced from the host tree when [DrawnOsMenuHost] wraps it from the
     * window root. Null falls back to [LocalDesignTheme.current].
     */
    internal var theme by mutableStateOf<ResolvedTheme?>(null)

    /** The normalized entries of the currently open menu, or empty when no menu is open. */
    val normalizedEntries: List<OsMenuEntry>
        get() = active?.entries ?: emptyList()

    override fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle {
        // At most one menu is open per window; dismiss any previous session first.
        active?.let { previous ->
            active = null
            previous.once.dismissed()
        }
        val normalized = normalizeOsMenu(request.entries)
        var session: ActiveMenu? = null
        val once = OsMenuResultOnce { result ->
            if (active === session) active = null
            onResult(result)
        }
        if (normalized.isEmpty()) {
            once.dismissed()
            return OsMenuHandle {}
        }
        val created = ActiveMenu(request.copy(entries = normalized), normalized, once)
        session = created
        active = created
        return OsMenuHandle {
            if (active === created) active = null
            once.dismissed()
        }
    }

    /** Chooses the item at [path] if it resolves to an enabled [OsMenuEntry.Item]. */
    fun choose(path: List<Int>) {
        val current = active ?: return
        if (resolveOsMenuPath(current.entries, path) == null) return
        active = null
        current.once.chosen(path)
    }

    /** Closes the active menu without choosing an entry. */
    fun dismiss() {
        val current = active ?: return
        active = null
        current.once.dismissed()
    }

    /** Opens or closes the enabled submenu at [path]. */
    fun toggleSubmenu(path: List<Int>) {
        val current = active ?: return
        val submenu = entryAt(current.entries, path) as? OsMenuEntry.Submenu ?: return
        if (!submenu.enabled || submenu.children.isEmpty()) return
        current.openSubmenuPath = if (isPrefix(path, current.openSubmenuPath)) {
            path.dropLast(1)
        } else {
            path
        }
    }

    /** Opens the enabled submenu at [path]. */
    fun openSubmenu(path: List<Int>) {
        val current = active ?: return
        val submenu = entryAt(current.entries, path) as? OsMenuEntry.Submenu ?: return
        if (!submenu.enabled || submenu.children.isEmpty()) return
        current.openSubmenuPath = path
    }

    /** Closes the submenu at [path] and any deeper submenus under it. */
    fun closeSubmenu(path: List<Int>) {
        val current = active ?: return
        if (isPrefix(path, current.openSubmenuPath)) {
            current.openSubmenuPath = path.dropLast(1)
        }
    }

    /** Whether the submenu at [path] is currently open. */
    fun isSubmenuOpen(path: List<Int>): Boolean {
        val current = active ?: return false
        return isPrefix(path, current.openSubmenuPath)
    }
}

/**
 * Provides [presenter] as [LocalOsMenuPresenter] and renders its active popup overlay.
 */
@Composable
fun DrawnOsMenuHost(
    presenter: DrawnOsMenuPresenter = remember { DrawnOsMenuPresenter() },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOsMenuPresenter provides presenter) {
        Box {
            content()
            DrawnOsMenuOverlay(presenter)
        }
    }
}

@Composable
internal fun DrawnOsMenuOverlay(presenter: DrawnOsMenuPresenter) {
    val session = presenter.active ?: return
    val theme = presenter.theme ?: LocalDesignTheme.current
    DrawnOsMenuLevel(
        presenter = presenter,
        entries = session.entries,
        prefix = emptyList(),
        positionProvider = remember(session.request.anchor, session.request.layoutDirection) {
            AnchorRectPositionProvider(session.request.anchor, session.request.layoutDirection)
        },
        theme = theme,
        onDismiss = { presenter.dismiss() },
    )
}

@Composable
private fun DrawnOsMenuLevel(
    presenter: DrawnOsMenuPresenter,
    entries: List<OsMenuEntry>,
    prefix: List<Int>,
    positionProvider: PopupPositionProvider,
    theme: ResolvedTheme,
    onDismiss: () -> Unit,
) {
    val style = theme.rules.container(ContainerRole.Menu, theme)
    val popupShape = theme.overlay?.popupCornerRadius?.takeIf { it > 0 }?.let { RoundedCornerShape(it.dp) }
        ?: style.shape
    val popupElevation = theme.overlay?.popupElevation?.takeIf { it > 0 }?.dp ?: style.elevation
    val popupRaised = if (popupElevation.value <= 0f) {
        Modifier
    } else {
        theme.rules.elevation(Modifier, popupElevation, popupShape, theme)
    }
    val explicitPad = theme.overlay?.popupPadding
    val popupPadding = if (explicitPad != null && explicitPad > 0) {
        Modifier.padding(explicitPad.dp)
    } else {
        Modifier.padding(vertical = theme.space(SpaceRole.Xs))
    }

    val focusableIndices = remember(entries) {
        entries.indices.filter { index ->
            when (val entry = entries[index]) {
                is OsMenuEntry.Item -> entry.enabled
                is OsMenuEntry.Submenu -> entry.enabled && entry.children.isNotEmpty()
                is OsMenuEntry.Separator -> false
            }
        }
    }
    val initialFocused = remember(entries) {
        entries.indexOfFirst { it is OsMenuEntry.Item && it.enabled && it.checked }
            .takeIf { it >= 0 }
            ?: focusableIndices.firstOrNull()
            ?: -1
    }
    var focusedIndex by remember(entries) { mutableIntStateOf(initialFocused) }
    val popupRequester = remember(prefix) { FocusRequester() }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss,
    ) {
        LaunchedEffect(entries, prefix) {
            try {
                popupRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Popup left composition before focus landed.
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
            Column(
                Modifier
                    .testTag(drawnOsMenuPopupTestTag(prefix))
                    .focusRequester(popupRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape, Key.Back -> {
                                onDismiss()
                                true
                            }
                            Key.DirectionDown -> {
                                if (focusableIndices.isEmpty()) return@onPreviewKeyEvent false
                                val pos = focusableIndices.indexOf(focusedIndex)
                                focusedIndex = focusableIndices[(pos + 1).mod(focusableIndices.size)]
                                true
                            }
                            Key.DirectionUp -> {
                                if (focusableIndices.isEmpty()) return@onPreviewKeyEvent false
                                val pos = focusableIndices.indexOf(focusedIndex)
                                val next = if (pos <= 0) focusableIndices.lastIndex else pos - 1
                                focusedIndex = focusableIndices[next]
                                true
                            }
                            Key.MoveHome -> {
                                focusableIndices.firstOrNull()?.let { focusedIndex = it }
                                focusableIndices.isNotEmpty()
                            }
                            Key.MoveEnd -> {
                                focusableIndices.lastOrNull()?.let { focusedIndex = it }
                                focusableIndices.isNotEmpty()
                            }
                            Key.Enter, Key.Spacebar -> {
                                val target = entries.getOrNull(focusedIndex) ?: return@onPreviewKeyEvent false
                                val path = prefix + focusedIndex
                                when (target) {
                                    is OsMenuEntry.Item -> if (target.enabled) {
                                        presenter.choose(path)
                                        true
                                    } else {
                                        false
                                    }
                                    is OsMenuEntry.Submenu -> if (target.enabled && target.children.isNotEmpty()) {
                                        presenter.openSubmenu(path)
                                        true
                                    } else {
                                        false
                                    }
                                    is OsMenuEntry.Separator -> false
                                }
                            }
                            Key.DirectionRight -> {
                                val target = entries.getOrNull(focusedIndex) as? OsMenuEntry.Submenu
                                    ?: return@onPreviewKeyEvent false
                                if (target.enabled && target.children.isNotEmpty()) {
                                    presenter.openSubmenu(prefix + focusedIndex)
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.DirectionLeft -> {
                                if (prefix.isNotEmpty()) {
                                    presenter.closeSubmenu(prefix)
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                    .then(popupRaised)
                    .clip(popupShape)
                    .background(style.container, popupShape)
                    .border(style.borderWidth, style.borderColor, popupShape)
                    .then(popupPadding)
                    .menuWidth(),
            ) {
                val contentColor = theme.color(ColorRole.OnSurface)
                val mutedColor = theme.color(ColorRole.OnSurfaceVariant)
                val highlightColor = theme.color(ColorRole.SurfaceVariant)
                val labelStyle = theme.pickerTextStyle(TypeRole.Body, color = contentColor)
                val shortcutStyle = theme.pickerTextStyle(TypeRole.Caption, color = mutedColor)

                entries.forEachIndexed { index, entry ->
                    val path = prefix + index
                    val isFocused = index == focusedIndex
                    when (entry) {
                        is OsMenuEntry.Separator -> {
                            theme.rules.controlWidgets.Divider(
                                vertical = false,
                                modifier = Modifier
                                    .testTag(drawnOsMenuEntryTestTag(path))
                                    .padding(vertical = theme.space(SpaceRole.Xs)),
                                theme = theme,
                            )
                        }
                        is OsMenuEntry.Item -> {
                            val rowAlpha = if (entry.enabled) 1f else DISABLED_ENTRY_ALPHA
                            Row(
                                modifier = Modifier
                                    .testTag(drawnOsMenuEntryTestTag(path))
                                    .fillMaxWidth()
                                    .background(if (isFocused || entry.checked) highlightColor else Color.Transparent)
                                    .clickable(enabled = entry.enabled) { presenter.choose(path) }
                                    .semantics {
                                        if (entry.checked) selected = true
                                        if (!entry.enabled) disabled()
                                    }
                                    .alpha(rowAlpha)
                                    .padding(
                                        horizontal = theme.space(SpaceRole.Sm),
                                        vertical = theme.space(SpaceRole.Xs),
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs)),
                            ) {
                                if (entry.checked) {
                                    RoleIcon(IconRole.Check, contentColor, theme)
                                }
                                entry.icon?.let { role ->
                                    if (!entry.checked || role != IconRole.Check) {
                                        RoleIcon(role, contentColor, theme)
                                    }
                                }
                                ThemedText(
                                    text = entry.label,
                                    style = labelStyle.copy(color = contentColor),
                                )
                                entry.shortcutLabel?.let { shortcut ->
                                    Spacer(Modifier.width(theme.space(SpaceRole.Md)))
                                    ThemedText(
                                        text = shortcut,
                                        style = shortcutStyle.copy(color = mutedColor),
                                    )
                                }
                            }
                        }
                        is OsMenuEntry.Submenu -> {
                            val rowAlpha = if (entry.enabled) 1f else DISABLED_ENTRY_ALPHA
                            val submenuOpen = entry.enabled && presenter.isSubmenuOpen(path)
                            Box(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .testTag(drawnOsMenuEntryTestTag(path))
                                        .fillMaxWidth()
                                        .background(if (isFocused || submenuOpen) highlightColor else Color.Transparent)
                                        .clickable(enabled = entry.enabled) { presenter.toggleSubmenu(path) }
                                        .semantics {
                                            if (!entry.enabled) disabled()
                                        }
                                        .alpha(rowAlpha)
                                        .padding(
                                            horizontal = theme.space(SpaceRole.Sm),
                                            vertical = theme.space(SpaceRole.Xs),
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs)),
                                ) {
                                    entry.icon?.let { role ->
                                        RoleIcon(role, contentColor, theme)
                                    }
                                    ThemedText(
                                        text = entry.label,
                                        style = labelStyle.copy(color = contentColor),
                                    )
                                    Spacer(Modifier.width(theme.space(SpaceRole.Sm)))
                                    ThemedText(
                                        text = SUBMENU_CHEVRON,
                                        style = shortcutStyle.copy(color = mutedColor),
                                    )
                                }
                                if (submenuOpen) {
                                    DrawnOsMenuLevel(
                                        presenter = presenter,
                                        entries = entry.children,
                                        prefix = path,
                                        positionProvider = SubmenuPositionProvider,
                                        theme = theme,
                                        onDismiss = { presenter.closeSubmenu(path) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DISABLED_ENTRY_ALPHA = 0.38f
private const val SUBMENU_CHEVRON = "\u25B8"

private class AnchorRectPositionProvider(
    private val anchor: NativeRect,
    private val preferredDirection: LayoutDirection,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rect = IntRect(
            left = anchor.x,
            top = anchor.y,
            right = anchor.x + anchor.width,
            bottom = anchor.y + anchor.height,
        )
        return BelowAnchorPositionProvider.calculatePosition(
            anchorBounds = rect,
            windowSize = windowSize,
            layoutDirection = preferredDirection,
            popupContentSize = popupContentSize,
        )
    }
}

private object SubmenuPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            if (anchorBounds.right + popupContentSize.width <= windowSize.width) {
                anchorBounds.right
            } else {
                (anchorBounds.left - popupContentSize.width).coerceAtLeast(0)
            }
        } else {
            if (anchorBounds.left - popupContentSize.width >= 0) {
                anchorBounds.left - popupContentSize.width
            } else {
                anchorBounds.right.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
            }
        }
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = anchorBounds.top.coerceIn(0, maxY)
        return IntOffset(x, y)
    }
}

private fun entryAt(entries: List<OsMenuEntry>, path: List<Int>): OsMenuEntry? {
    if (path.isEmpty()) return null
    var level = entries
    for ((depth, index) in path.withIndex()) {
        val entry = level.getOrNull(index) ?: return null
        if (depth == path.lastIndex) return entry
        val submenu = entry as? OsMenuEntry.Submenu ?: return null
        level = submenu.children
    }
    return null
}

private fun isPrefix(prefix: List<Int>, path: List<Int>): Boolean {
    if (prefix.size > path.size) return false
    for (i in prefix.indices) {
        if (prefix[i] != path[i]) return false
    }
    return true
}
