package cn.edu.bucea.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cn.edu.bucea.library.AppThemeColor

@Composable
internal fun LibraryTheme(themeColor: AppThemeColor, content: @Composable () -> Unit) {
    val colorScheme = remember(themeColor) {
        val accent = Color(themeColor.primaryArgb)
        val ink = lerp(accent, Color(0xFF151318), 0.72f)
        val muted = lerp(accent, Color(0xFF59565E), 0.8f)
        val soft = lerp(accent, Color.White, 0.92f)
        val paper = lerp(accent, Color.White, 0.97f)
        val line = lerp(accent, Color.White, 0.82f)
        val bannerEnd = lerp(ink, accent, 0.45f)
        lightColorScheme(
            primary = accent, onPrimary = Color.White,
            primaryContainer = soft, onPrimaryContainer = ink, inversePrimary = soft,
            secondary = bannerEnd, onSecondary = Color.White,
            secondaryContainer = soft, onSecondaryContainer = ink,
            tertiary = accent, onTertiary = Color.White,
            tertiaryContainer = soft, onTertiaryContainer = ink,
            background = paper, onBackground = ink,
            surface = Color.White, onSurface = ink, surfaceTint = accent,
            surfaceVariant = soft, onSurfaceVariant = muted,
            outline = lerp(accent, Color(0xFF76717C), 0.7f), outlineVariant = line,
            inverseSurface = ink, inverseOnSurface = paper,
            surfaceBright = Color.White, surfaceDim = line,
            surfaceContainerHigh = Color.White, surfaceContainerHighest = soft,
            surfaceContainer = Color.White, surfaceContainerLow = paper,
            surfaceContainerLowest = Color.White,
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val SoftAccent: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val BannerEnd: Color @Composable get() = MaterialTheme.colorScheme.secondary
