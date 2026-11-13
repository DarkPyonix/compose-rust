package dev.darkpyonix.composerust.ui.platform

import android.content.Context
import androidx.activity.ComponentActivity
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.clampZoomLevel
import dev.darkpyonix.composerust.runtime.platformZoomLevelStore

/**
 * Keeps the application's zoom level in its own preferences, which are one per application.
 *
 * Called by the generated Activity on every creation, before the content is composed. The
 * application context rather than the Activity, so a recreated Activity leaves nothing of
 * the old one held.
 */
fun installZoomLevelStore(activity: ComponentActivity) {
    val context = activity.applicationContext
    platformZoomLevelStore = { PreferencesZoomLevel(context) }
}

private class PreferencesZoomLevel(private val context: Context) : ZoomLevelStore {
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): Int? =
        if (preferences.contains(KEY)) clampZoomLevel(preferences.getInt(KEY, 0)) else null

    override fun save(level: Int) {
        preferences.edit().putInt(KEY, clampZoomLevel(level)).apply()
    }

    private companion object {
        const val PREFERENCES = "compose-rust"
        const val KEY = "zoom_level"
    }
}
