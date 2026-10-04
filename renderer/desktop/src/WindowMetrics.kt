package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.dp

/**
 * The caption strip Windows gives up, and the room its three buttons take at the trailing
 * edge.
 *
 * Fixed rather than measured, because the native side has to agree with it: it answers the
 * hit test for this strip and has to know which part of it is buttons that take ordinary
 * clicks and which part drags the window. scripts/tests/windows-caption-metrics.test.sh
 * fails if the height here and the one in win32_window.c stop agreeing.
 */
internal val windowsCaptionHeight = 32.dp
