package com.t30watch.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.t30watch.data.settings.DarkMode

/**
 * Material 3 theme from the user's Appearance choices (More → Appearance).
 * "Wallpaper" uses the phone's wallpaper colours on Android 12+; every other style is a fixed palette.
 */
@Composable
fun T30Theme(
    themeId: String = Palettes.WALLPAPER.id,
    darkMode: DarkMode = DarkMode.SYSTEM,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val palette = Palettes.byId(themeId)
    val target = when {
        palette.id == Palettes.WALLPAPER.id && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx).let { if (amoled) it.blackened() else it } else dynamicLightColorScheme(ctx)
        }
        dark -> palette.darkScheme(amoled)
        else -> palette.lightScheme()
    }
    MaterialTheme(colorScheme = target.animated(), content = content)
}

private fun ColorScheme.blackened() = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
)

/** Fades between styles instead of snapping when the user picks a new one. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable fun Color.a(): Color = animateColorAsState(this, tween(400), label = "theme").value
    return copy(
        primary = primary.a(), onPrimary = onPrimary.a(),
        primaryContainer = primaryContainer.a(), onPrimaryContainer = onPrimaryContainer.a(),
        secondary = secondary.a(), onSecondary = onSecondary.a(),
        secondaryContainer = secondaryContainer.a(), onSecondaryContainer = onSecondaryContainer.a(),
        tertiary = tertiary.a(), onTertiary = onTertiary.a(),
        tertiaryContainer = tertiaryContainer.a(), onTertiaryContainer = onTertiaryContainer.a(),
        background = background.a(), onBackground = onBackground.a(),
        surface = surface.a(), onSurface = onSurface.a(),
        surfaceVariant = surfaceVariant.a(), onSurfaceVariant = onSurfaceVariant.a(),
        surfaceContainerLowest = surfaceContainerLowest.a(), surfaceContainerLow = surfaceContainerLow.a(),
        surfaceContainer = surfaceContainer.a(), surfaceContainerHigh = surfaceContainerHigh.a(),
        surfaceContainerHighest = surfaceContainerHighest.a(),
    )
}
