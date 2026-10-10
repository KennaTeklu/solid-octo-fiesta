package app.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.reader.data.repo.AppTheme
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * Material 3 theme. Supports:
 *  - Dynamic color on Android 12+ (matches the user's wallpaper, controlled by [SettingsRepository]).
 *  - Light / dark / system mode.
 *  - A calm teal fallback palette for older devices or when dynamic color is off.
 */
@Composable
fun ReaderTheme(
    theme: AppTheme = AppTheme.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = when (theme) {
        AppTheme.Light -> false
        AppTheme.Dark -> true
        AppTheme.System -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        systemDark -> DarkColors
        else -> LightColors
    }

    // Edge-to-edge: make the status / nav bars transparent and tint icons.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !systemDark
                controller.isAppearanceLightNavigationBars = !systemDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ReaderTypography,
        content = content
    )
}

// Calm teal fallback palette — keeps the app legible when Material You is off.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5E5D6),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A6359),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DA),
    onSecondaryContainer = Color(0xFF062016),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7975),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD0BB),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB5E5D6),
    secondary = Color(0xFFB1CCBF),
    onSecondary = Color(0xFF1C352B),
    secondaryContainer = Color(0xFF334B41),
    onSecondaryContainer = Color(0xFFCDE8DA),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF889390),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

/** Color used to highlight the current sentence in the reader. */
val SentenceHighlight = Color(0xFFFFF2A8)
val SentenceHighlightDark = Color(0xFF6B5520)
