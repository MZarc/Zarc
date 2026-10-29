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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.zarc.vault.R

val AndikaFontFamily = FontFamily(
    Font(R.font.andika_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.andika_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.andika_bold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.andika_regular, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.andika_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.andika_bold_italic, FontWeight.Bold, FontStyle.Italic)
)

/**
 * Hallmark-crafted design token palette for Zarc.
 *
 * Dark-first, OLED-optimized palette featuring:
 * - Four surface layers tinted towards the calm midnight anchor (L 10-18, subtle cobalt bias).
 * - Restrained lavender accent (0xFFB389F9) serving as an intentional signal.
 * - Conforming to APCA / WCAG 7:1 contrast for high legibility in sensitive viewing environments.
 * - Zero artificial gradients; clear physical hairlines and concentric elevation.
 */
private val ZarcDarkColors = darkColorScheme(
    primary = Color(0xFFB389F9),
    onPrimary = Color(0xFF24005A),
    primaryContainer = Color(0xFF421877),
    onPrimaryContainer = Color(0xFFECD9FF),
    secondary = Color(0xFF7FD4C1),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F3630),
    onSecondaryContainer = Color(0xFFB8F2E4),
    tertiary = Color(0xFFF2B36B),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF3D2705),
    onTertiaryContainer = Color(0xFFFFDFB3),
    background = Color(0xFF07080A),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFFA2A8B6),
    surfaceContainerLowest = Color(0xFF0A0B0F),
    surfaceContainerLow = Color(0xFF12141A),
    surfaceContainer = Color(0xFF161922),
    surfaceContainerHigh = Color(0xFF1E222E),
    surfaceContainerHighest = Color(0xFF262B3A),
    outline = Color(0xFF3E4658),
    outlineVariant = Color(0xFF262C3A),
    error = Color(0xFFFF6666),
    onError = Color(0xFF380000),
    errorContainer = Color(0xFF4A0A0A),
    onErrorContainer = Color(0xFFFFD1D1),
    inverseSurface = Color(0xFFF2F4F8),
    inverseOnSurface = Color(0xFF161922),
    scrim = Color(0xFF000000),
)

/** Domain category colours of the gallery. */
object ZarcPalette {
    /** Photos & camera roll. */
    val photos = Color(0xFFB389F9)

    /** Encrypted videos. */
    val videos = Color(0xFFF2B36B)

    /** Private stealth vault. */
    val privateArea = Color(0xFFD6BBFB)

    /** 30-day recycle bin. */
    val bin = Color(0xFF8A93A6)

    /** Favourites collection. */
    val favourite = Color(0xFFFFC44D)

    /** Verified cryptographic seal. */
    val verified = Color(0xFF4EDB95)
}

/**
 * Typography token scale using the Andika font family.
 *
 * Conforms to Hallmark Floor 9: labels, table heads, meta tags and nav links
 * are set untracked, sentence-cased in the body face.
 */
private val ZarcTypography = Typography(
    displaySmall = TextStyle(fontFamily = AndikaFontFamily, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = AndikaFontFamily, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = AndikaFontFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = AndikaFontFamily, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = AndikaFontFamily, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = AndikaFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = AndikaFontFamily, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = AndikaFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = AndikaFontFamily, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = AndikaFontFamily, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = AndikaFontFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = AndikaFontFamily, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal),
)

/** The Hallmark-crafted dark theme of Zarc. */
@Composable
fun ZarcTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    // The gallery is strictly dark-only, even when the system is in light mode.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = ZarcDarkColors,
        typography = ZarcTypography,
        content = content,
    )
}
