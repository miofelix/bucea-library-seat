package cn.edu.bucea.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.bucea.library.AppThemeColor

@Composable
internal fun LibraryTheme(themeColor: AppThemeColor, content: @Composable () -> Unit) {
    val colorScheme = remember(themeColor) { libraryColorScheme(themeColor) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LibraryTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

internal fun libraryColorScheme(themeColor: AppThemeColor): androidx.compose.material3.ColorScheme {
    val accent = Color(themeColor.primaryArgb)
    val onAccent = Color(themeColor.onPrimaryArgb)
    val readableAccent = Color(themeColor.readableAccentArgb)
    val ink = Color(0xFF272729)
    val muted = Color(0xFF707075)
    val soft = lerp(accent, Color.White, 0.90f)
    val paper = Color(0xFFFAFAFA)
    val line = Color(0xFFE7E7E9)
    val accentDeep = lerp(readableAccent, Color.Black, 0.15f)
    return lightColorScheme(
        // Default text buttons, outlined buttons and radio controls also use primary.
        primary = readableAccent, onPrimary = Color.White,
        primaryContainer = soft, onPrimaryContainer = readableAccent, inversePrimary = soft,
        secondary = accentDeep, onSecondary = Color.White,
        secondaryContainer = soft, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = soft, onTertiaryContainer = ink,
        background = paper, onBackground = ink,
        surface = Color.White, onSurface = ink, surfaceTint = accent,
        surfaceVariant = soft, onSurfaceVariant = muted,
        outline = Color(0xFF96969C), outlineVariant = line,
        inverseSurface = ink, inverseOnSurface = paper,
        surfaceBright = Color.White, surfaceDim = line,
        surfaceContainerHigh = Color.White, surfaceContainerHighest = soft,
        surfaceContainer = Color.White, surfaceContainerLow = paper,
        surfaceContainerLowest = Color.White,
    )
}

private val LibraryTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
// Accent is also used for small text and outlined controls on white/soft surfaces.
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val SoftAccent: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
