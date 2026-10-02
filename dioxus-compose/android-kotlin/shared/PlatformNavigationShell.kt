package dioxus.compose.foundation

import dioxus.compose.protocol.IconRole

/**
 * One destination, in the form a platform's own navigation chrome can take.
 *
 * A `NavigationItem` node carries a label and an icon role as properties, and a platform bar
 * wants exactly those two and nothing else. Handing over the node id as well lets the shell
 * report a choice back as that destination's own click, which is what keeps the event
 * contract identical to the drawn bar's.
 */
data class ShellDestination(
    val nodeId: Int,
    val label: String,
    val icon: IconRole?,
    val enabled: Boolean,
)

/**
 * The platform's own navigation chrome, where it has one worth more than ours.
 *
 * The interpreter is one set of sources compiled for every target, so it cannot name a
 * platform type. It can only ask. This is the whole of the question: is something installed
 * that would rather draw the strip itself, and if so, here are the destinations.
 *
 * Nothing installs one by default. With the field left null the navigation widget draws the
 * bar it has always drawn, which is what desktop, Android, the web and older iOS get.
 */
interface PlatformNavigationShell {
    /**
     * Whether this shell puts the strip on the screen, so the Renderer must not.
     *
     * Asked during composition and answered without touching the platform, because the
     * answer decides what gets laid out. A shell that cannot be stood up on this machine
     * answers false and costs nothing.
     */
    val drawsStrip: Boolean

    /**
     * How much room the shell's strip takes along the bottom edge, in density independent
     * pixels.
     *
     * Content is drawn under the strip rather than above it, because a bar that reflects
     * what is behind it needs something behind it. What this measurement is for is the
     * things that must stay clear of the strip anyway, the transient message above all.
     */
    val stripHeight: Float

    /**
     * Room the shell's own title bar takes along the top edge, in density independent
     * pixels, or zero where it has none.
     *
     * Unlike the strip along the bottom, this one is padding and not just a warning. A
     * title bar drawn by the platform sits over the top of the screen, and content left
     * underneath it is content nobody can read.
     *
     * Read during composition, so an implementation that measures it after a layout pass
     * has to keep it somewhere Compose observes, or the first frame's zero is the only
     * answer anyone ever sees.
     */
    val titleHeight: Float

    /**
     * Puts these destinations on the platform's chrome, with `selected` marked.
     *
     * Called again whenever the destinations or the selection change, so an implementation
     * updates what differs rather than rebuilding. `onSelect` is invoked on the thread that
     * called this, which is the UI thread the interpreter already runs on.
     */
    fun present(destinations: List<ShellDestination>, selected: Int, onSelect: (Int) -> Unit)

    /** Takes the strip back down. Called when the last navigation leaves the tree. */
    fun dismiss()
}

/**
 * The shell this process runs with, or null when it draws its own strip.
 *
 * A settable global rather than a CompositionLocal because the thing behind it is a window,
 * of which there is one on the platforms that have a shell at all, and because the platform
 * entry point that knows about the window runs before any composition exists.
 */
var platformNavigationShell: PlatformNavigationShell? = null
