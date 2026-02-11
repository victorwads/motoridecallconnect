package dev.wads.motoridecallconnect.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF002F67),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = BlueContainerLight,
    secondary = Steel80,
    onSecondary = Color(0xFF1D324B),
    secondaryContainer = SteelContainerDark,
    onSecondaryContainer = SteelContainerLight,
    tertiary = Teal80,
    onTertiary = Color(0xFF003738),
    tertiaryContainer = TealContainerDark,
    onTertiaryContainer = TealContainerLight,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = OnSurfaceDark,
    inverseOnSurface = Color(0xFF2D3138),
    inversePrimary = Blue40
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Steel40,
    onSecondary = Color.White,
    secondaryContainer = SteelContainerLight,
    onSecondaryContainer = Color(0xFF071D35),
    tertiary = Teal40,
    onTertiary = Color.White,
    tertiaryContainer = TealContainerLight,
    onTertiaryContainer = Color(0xFF002021),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = Color(0xFF2D3138),
    inverseOnSurface = Color(0xFFEFF1F8),
    inversePrimary = Blue80
)

@Composable
fun MotoRideCallConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color can generate low-contrast palettes depending on wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
