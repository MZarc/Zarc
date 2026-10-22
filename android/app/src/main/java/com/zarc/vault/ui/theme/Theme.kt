package com.zarc.vault.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * The colour scheme of the gallery.
 *
 * Zarc is a dark only application: the gallery is used in the dark, the
 * content is sensitive, and a bright surface would leak the screen content to
 * anybody who can see it. The palette follows the One UI ideas: a near black
 * background, slightly lifted surfaces, one calm accent and a category colour
 * per media type.
 */
private val ZarcDarkColors = darkColorScheme(
    primary = Color(0xFF4CA5FF),
    onPrimary = Color(0xFF00274A),
    primaryContainer = Color(0xFF00315C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF7FD4C1),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFFF2B36B),
    onTertiary = Color(0xFF452B00),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF3F3F5),
    surface = Color(0xFF0B0B0D),
    onSurface = Color(0xFFF3F3F5),
    surfaceVariant = Color(0xFF1C1C20),
    onSurfaceVariant = Color(0xFF9C9CA4),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF1A1A1E),
    surfaceContainerHighest = Color(0xFF222227),
    outline = Color(0xFF3A3A41),
    outlineVariant = Color(0xFF26262B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
    inverseSurface = Color(0xFFF3F3F5),
    inverseOnSurface = Color(0xFF1A1A1E),
    scrim = Color(0xFF000000),
)

/** The category colours of the gallery, one per media kind. */
object ZarcPalette {
    /** Pictures. */
    val photos = Color(0xFF4CA5FF)

    /** Videos. */
    val videos = Color(0xFFF2B36B)

    /** The private area. */
    val privateArea = Color(0xFFC79BFF)

    /** The recycle bin. */
    val bin = Color(0xFF8A8A93)
}

/** The typography of the gallery: a little tighter than the Material default. */
private val ZarcTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

/** The dark theme of Zarc. */
@Composable
fun ZarcTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    // The gallery is dark only, even when the system is in light mode.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = ZarcDarkColors,
        typography = ZarcTypography,
        content = content,
    )
}
