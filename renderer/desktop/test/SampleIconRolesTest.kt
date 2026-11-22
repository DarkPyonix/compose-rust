package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.iconGeometry
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleIconRolesTest {
    @Test
    fun fr16_sample_roles_have_artwork_in_every_system_and_scheme() {
        val roles = listOf(
            IconRole.Lock,
            IconRole.Favorite,
            IconRole.Book,
            IconRole.Play,
            IconRole.Shuffle,
            IconRole.ExpandMore,
            IconRole.Person,
            IconRole.Clock,
            IconRole.Tune,
            IconRole.Chat,
            IconRole.Star,
            IconRole.Bag,
            IconRole.Bell,
            IconRole.Share,
            IconRole.Language,
            IconRole.CompactOverlay,
            IconRole.Calculator,
            IconRole.Refresh,
            IconRole.Delete,
        )
        assertEquals(19, roles.distinct().size)
        for (role in roles) {
            val geometry = iconGeometry(role)
            assertTrue(geometry.strokes.isNotEmpty() || geometry.dots.isNotEmpty(), "$role has no artwork")
            for (system in DesignSystem.entries) {
                for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
                    val theme = resolveTheme(Theme(system, DesignSystem.Material3, scheme, false), HostPlatform.MacOs, false)
                    val style = theme.rules.icon(role, theme)
                    assertTrue(style.size.value > 0f, "$system $scheme $role has no size")
                    assertTrue(style.strokeWidth.value > 0f, "$system $scheme $role has no stroke")
                }
            }
        }
    }
}
