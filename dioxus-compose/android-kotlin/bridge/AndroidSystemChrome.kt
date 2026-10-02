package dioxus.compose.ui.platform

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import dioxus.compose.foundation.SystemChrome
import dioxus.compose.foundation.systemChrome

/**
 * Hands the window's system strips to the renderer.
 *
 * Two things, both of which have to be right for an application to reach its own edges.
 *
 * The window is told to draw under the strips, with no scrim in either of them. Asking to
 * draw edge to edge and stopping there is not enough: the default puts a translucent band
 * over the navigation bar so that a system that knows nothing about the application can
 * still tell its icons apart from whatever is behind them. The band is a colour the
 * application never chose, and it is visible: a page of #f2f2f7 came out with a #fefefe
 * strip along the bottom, which is a line across the screen that no other application on
 * the device has. We know what is behind the icons because we drew it, so the scrim goes
 * and the icons are coloured directly instead.
 *
 * That is the second thing. Which way round the clock and the gesture bar are drawn
 * follows the theme the application asked for rather than the device's setting: a light
 * application on a phone in dark mode would otherwise get white icons on its own white
 * bar.
 *
 * Called by the generated Activity, and called again on every recreation because the
 * window is new each time.
 */
fun installSystemChrome(activity: ComponentActivity) {
    activity.enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    val window = activity.window
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // The system adds a scrim of its own on top of the styles above unless this is
        // turned off, and it is the one that was showing.
        window.isNavigationBarContrastEnforced = false
    }
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    systemChrome = object : SystemChrome {
        override fun setDarkIcons(dark: Boolean) {
            // Android says it the other way round: the flag describes the background the
            // icons sit on, not the icons.
            controller.isAppearanceLightStatusBars = dark
            controller.isAppearanceLightNavigationBars = dark
        }
    }
}
