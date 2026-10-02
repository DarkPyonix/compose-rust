package dioxus.compose.ui.platform

import dioxus.compose.foundation.PlatformNavigationShell
import dioxus.compose.foundation.ShellDestination
import dioxus.compose.protocol.IconRole
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UINavigationController
import platform.UIKit.UITabBarController
import platform.UIKit.UITabBarControllerDelegateProtocol
import platform.UIKit.UITabBarItem
import platform.UIKit.UITabBarMinimizeBehaviorAutomatic
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.setTabBarItem
import platform.UIKit.tabBarItem
import platform.darwin.NSObject
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.cinterop.useContents

/** How tall a tab bar is before the system has laid one out. */
private const val ASSUMED_TAB_BAR_HEIGHT = 49f

/**
 * The name of the SF Symbol that says this.
 *
 * The same reasoning the drawn icons follow: the Host sends a meaning and the platform
 * decides the picture. Here the platform has already drawn it, so all that is needed is its
 * name. A role with no good symbol gets none and the tab shows its label alone, which is a
 * legal tab bar item and not a missing one.
 */
private fun symbolName(role: IconRole?): String? = when (role) {
    IconRole.Back -> "chevron.backward"
    IconRole.Forward -> "chevron.forward"
    IconRole.Close -> "xmark"
    IconRole.Search -> "magnifyingglass"
    IconRole.Add -> "plus"
    IconRole.Check -> "checkmark"
    IconRole.Settings -> "gearshape"
    IconRole.More -> "ellipsis"
    IconRole.Home -> "house"
    IconRole.List -> "list.bullet"
    IconRole.Inbox -> "tray"
    // What a window's own bar needs rather than a destination. A tab bar will not show
    // either of them, but the mapping is exhaustive on purpose: a role with no answer
    // here is a role somebody added without deciding what iOS draws for it.
    IconRole.Menu -> "line.3.horizontal"
    IconRole.History -> "clock.arrow.circlepath"
    null -> null
}

/**
 * The system's tab bar, standing in for the one the Renderer would otherwise draw.
 *
 * The content stays where it was. A `UITabBarController` is put around the Compose view
 * controller once, at launch, and the Compose view is inserted underneath the bar and left
 * there for the life of the process. Nothing is ever re-parented, so a change of tab does
 * not disturb the composition: the Host swaps the screen's subtree, exactly as it does when
 * the bar is drawn on this side.
 *
 * Lying under the bar rather than above it is the point. What makes the bar Liquid Glass
 * and not a grey strip is that the system can see what is behind it, and what is behind it
 * is the Compose surface.
 *
 * The tabs themselves hold nothing of the application. Each is a `UINavigationController`
 * over an empty transparent screen, so the destination's label names the tab at the bottom
 * and titles the bar at the top, and both bars are the system's to draw.
 */
internal class IosNavigationShell(
    private val tabs: UITabBarController,
) : PlatformNavigationShell {

    private val selection = TabSelectionDelegate()
    private var presented: List<ShellDestination> = emptyList()

    // Measured after UIKit has laid the bars out, and held where Compose observes it. A
    // plain getter would be read once, on the frame before the bars exist, and the zero it
    // answered then would be the only answer anything ever saw.
    private var measuredStrip by mutableFloatStateOf(ASSUMED_TAB_BAR_HEIGHT)
    private var measuredTitle by mutableFloatStateOf(0f)

    init {
        tabs.delegate = selection
    }

    override val drawsStrip: Boolean get() = true

    override val stripHeight: Float get() = measuredStrip

    override val titleHeight: Float get() = measuredTitle

    override fun present(
        destinations: List<ShellDestination>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        selection.onSelect = onSelect
        // Rebuilt only when the destinations themselves changed. Moving the selection is
        // the common case by far, and it must not cost a new set of view controllers: that
        // would restart the bar's own animation on every tap.
        if (destinations != presented) {
            presented = destinations
            tabs.setViewControllers(destinations.map(::tab), animated = false)
            tabs.tabBar.hidden = destinations.isEmpty()
            enableMinimizeOnScroll()
        }
        val index = selected.coerceIn(0, (destinations.size - 1).coerceAtLeast(0))
        if (destinations.isNotEmpty() && tabs.selectedIndex.toInt() != index) {
            tabs.selectedIndex = index.toULong()
        }
        measure()
    }

    override fun dismiss() {
        presented = emptyList()
        selection.onSelect = {}
        tabs.setViewControllers(emptyList<UIViewController>(), animated = false)
        tabs.tabBar.hidden = true
        measuredStrip = 0f
        measuredTitle = 0f
    }

    /**
     * Takes the bars' sizes from UIKit rather than assuming them.
     *
     * A tab bar is 49 points on one device, floats at another height on the next, and grows
     * by the home indicator on a third; the title bar grows by the status bar and by
     * whatever the notch needs. Both are laid out by the system and both are asked, after
     * forcing the layout that has not happened yet on the frame a navigation first arrives.
     */
    private fun measure() {
        tabs.view.layoutIfNeeded()
        val height = tabs.view.bounds.useContents { size.height }.toFloat()
        val barTop = tabs.tabBar.frame.useContents { origin.y }.toFloat()
        measuredStrip = when {
            tabs.tabBar.hidden -> 0f
            height > 0f && barTop > 0f -> height - barTop
            else -> ASSUMED_TAB_BAR_HEIGHT
        }
        val navigation = tabs.selectedViewController as? UINavigationController
        measuredTitle = navigation?.navigationBar
            ?.takeIf { !it.hidden }
            ?.frame
            ?.useContents { origin.y + size.height }
            ?.toFloat()
            ?: 0f
    }

    /**
     * Asks for the bar that floats over the content and shrinks as the page scrolls.
     *
     * Guarded by a selector probe rather than by the version gate a second time. The gate
     * already decided that this shell exists at all; this asks the narrower question the
     * next line actually depends on, which is whether this UIKit has the property. A build
     * made against an older SDK and run on a newer system reaches here too.
     */
    private fun enableMinimizeOnScroll() {
        val setter = NSSelectorFromString("setTabBarMinimizeBehavior:")
        if (!tabs.respondsToSelector(setter)) return
        tabs.tabBarMinimizeBehavior = UITabBarMinimizeBehaviorAutomatic
    }

    /**
     * One tab: a navigation controller over an empty screen, carrying the label twice.
     *
     * Twice because that is what an iOS application looks like. The label names the tab in
     * the bar along the bottom and titles the bar along the top, and both bars are drawn by
     * the system, which is the whole point of standing them up rather than drawing them.
     *
     * The screen inside is empty and transparent. What is under it is the Compose surface,
     * which is a sibling of this whole stack and is never moved into it.
     *
     * The navigation controller's own view is made untouchable, not just the empty screen
     * inside it. Its container view covers the whole tab, so leaving it touchable would
     * swallow every tap meant for the content underneath. The cost is that the title bar is
     * decoration: the first thing put in it that a user has to press needs this replaced
     * with a view that answers `hitTest` for the bar's rectangle alone.
     */
    private fun tab(destination: ShellDestination): UIViewController {
        val screen = UIViewController(nibName = null, bundle = null)
        screen.title = destination.label
        screen.view.backgroundColor = UIColor.clearColor
        screen.view.userInteractionEnabled = false

        val navigation = UINavigationController(rootViewController = screen)
        navigation.view.backgroundColor = UIColor.clearColor
        navigation.view.userInteractionEnabled = false
        val item = UITabBarItem(
            title = destination.label,
            image = symbolName(destination.icon)?.let { UIImage.systemImageNamed(it) },
            tag = 0,
        )
        item.enabled = destination.enabled
        navigation.tabBarItem = item
        return navigation
    }
}

/**
 * Turns a tap on the system's bar into the index that was chosen.
 *
 * `didSelectViewController` is reported for a user's choice and not for a selection set in
 * code, which is what keeps a selection arriving from the Host from bouncing straight back
 * out as a click.
 */
private class TabSelectionDelegate : NSObject(), UITabBarControllerDelegateProtocol {
    var onSelect: (Int) -> Unit = {}

    override fun tabBarController(
        tabBarController: UITabBarController,
        didSelectViewController: UIViewController,
    ) {
        val index = tabBarController.viewControllers?.indexOf(didSelectViewController) ?: return
        if (index >= 0) onSelect(index)
    }
}

/** What `installNativeNavigationShell` built, for the caller that has to hold on to it. */
internal class NativeNavigationShell(
    /** The controller the window takes as its root. */
    val root: UIViewController,
    /** The system's tab bar controller, put around the content. */
    val tabs: UITabBarController,
    /** The seam the interpreter talks to. */
    val shell: IosNavigationShell,
)

/**
 * Builds the container the window takes as its root, once, at launch.
 *
 * Three controllers, and the shape of them is not a matter of taste. A
 * `UITabBarController` turns `addChildViewController` into "append a tab", so the content
 * cannot be parented to it: the first `setViewControllers` would drop it again. Both the
 * content and the tab bar controller are therefore children of a plain container, and
 * neither is a child of the other.
 *
 * The content's *view*, on the other hand, goes inside the tab bar controller's view and
 * below everything that controller owns. That is what puts the Compose surface under the
 * bar, which is what gives the system something to refract, and it is also what lets a
 * touch in the content area reach Compose: the tab bar controller's own view would
 * otherwise swallow it.
 *
 * Nothing here is undone later. Tabs come and go through `present` and `dismiss`, and the
 * content is never moved.
 */
internal fun installNativeNavigationShell(content: UIViewController): NativeNavigationShell {
    val root = UIViewController(nibName = null, bundle = null)
    val tabs = UITabBarController()
    val fills = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight

    root.addChildViewController(content)
    root.addChildViewController(tabs)

    tabs.view.setFrame(root.view.bounds)
    tabs.view.setAutoresizingMask(fills)
    root.view.addSubview(tabs.view)

    content.view.setFrame(tabs.view.bounds)
    content.view.setAutoresizingMask(fills)
    tabs.view.insertSubview(content.view, atIndex = 0)

    content.didMoveToParentViewController(root)
    tabs.didMoveToParentViewController(root)
    tabs.tabBar.hidden = true

    val shell = IosNavigationShell(tabs)
    dioxus.compose.foundation.platformNavigationShell = shell
    return NativeNavigationShell(root, tabs, shell)
}

/**
 * The controller the window takes as its root.
 *
 * Where the system draws its own chrome as Liquid Glass, that is the container built above,
 * standing empty with its bar hidden until a navigation reaches the tree. Below that it is
 * the Compose controller and nothing else, exactly as it was before any of this existed:
 * there is no system glass down there to take, so a second shell would buy nothing and
 * would put an untested layout in front of the screen.
 *
 * `glass` is a parameter with the real answer as its default so that both sides of the
 * decision can be run on one machine. Nothing passes it.
 */
internal fun rendererRootViewController(
    content: UIViewController,
    glass: Boolean = systemDrawsLiquidGlass(),
): UIViewController = if (glass) installNativeNavigationShell(content).root else content
