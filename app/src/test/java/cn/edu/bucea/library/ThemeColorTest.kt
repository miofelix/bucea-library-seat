package cn.edu.bucea.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import cn.edu.bucea.library.ui.libraryColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    @Test fun savedColorsRoundTripAndUnknownPreferencesRetainTheDefault() {
        AppThemeColor.entries.forEach { color ->
            assertEquals(color, AppThemeColor.fromPreference(color.preferenceValue))
        }
        assertEquals(AppThemeColor.BUCEA_PURPLE, AppThemeColor.fromPreference(null))
        assertEquals(AppThemeColor.BUCEA_PURPLE, AppThemeColor.fromPreference("removed_theme"))
        assertEquals(AppThemeColor.NAILONG_YELLOW, AppThemeColor.fromPreference("nailong_yellow"))
    }

    @Test fun allThemesKeepButtonAndAccentTextReadable() {
        AppThemeColor.entries.forEach { color ->
            val scheme = libraryColorScheme(color)
            assertTrue("${color.label}: filled button text", contrast(scheme.onPrimary, scheme.primary) >= 4.5)
            assertTrue("${color.label}: default text and outlined buttons", contrast(scheme.primary, scheme.surface) >= 4.5)
            assertTrue("${color.label}: bright brand button text", contrast(scheme.onTertiary, scheme.tertiary) >= 4.5)
            assertTrue("${color.label}: accent text on white", contrast(scheme.onPrimaryContainer, scheme.surface) >= 4.5)
            assertTrue("${color.label}: accent text on tinted cards", contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5)
            assertTrue("${color.label}: existing accent buttons", contrast(scheme.onPrimaryContainer, Color.White) >= 4.5)
        }
    }

    @Test fun nailongYellowUsesBrightFillsWithDarkText() {
        val scheme = libraryColorScheme(AppThemeColor.NAILONG_YELLOW)
        assertEquals(0xFFFFCE32.toInt(), scheme.tertiary.toArgb())
        assertEquals(0xFF3C2B06.toInt(), scheme.onTertiary.toArgb())
        assertEquals(0xFF735300.toInt(), scheme.primary.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), scheme.onPrimary.toArgb())
        assertEquals(0xFF735300.toInt(), scheme.onPrimaryContainer.toArgb())
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
