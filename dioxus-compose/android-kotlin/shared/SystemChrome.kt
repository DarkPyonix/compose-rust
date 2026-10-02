package dioxus.compose.foundation

/**
 * The things the system draws over the window that are the application's to colour.
 *
 * A phone's status bar and navigation bar draw their own clock, icons and gesture bar, and
 * the application says whether those should be dark or light. Nothing else about them is
 * ours: the strips themselves are given to the parts of the tree that grow into them, and
 * what is drawn behind them is the window's own fill.
 *
 * It matters because the application chooses its colour scheme. A light application on a
 * phone set to dark mode gets a light bar with white icons on it and a clock nobody can
 * read, which is what happened here before this existed.
 *
 * A settable global rather than a CompositionLocal, for the same reason as
 * [platformNavigationShell]: the thing behind it is a window, of which there is one on the
 * platforms that have these strips, and the platform entry point that knows about the
 * window runs before any composition exists. Null everywhere else, where the system draws
 * nothing over the window and there is nothing to tell.
 */
interface SystemChrome {
    /**
     * Asks for dark icons in the system's strips, or light ones.
     *
     * Called with what the resolved theme says rather than what the device is set to: an
     * application that names a light scheme is light on a device in dark mode, and its
     * clock has to be readable against what it actually drew.
     */
    fun setDarkIcons(dark: Boolean)
}

/** The chrome this process runs with, or null where the system draws nothing over it. */
var systemChrome: SystemChrome? = null
