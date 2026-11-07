package com.t30watch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Colour styles the user can pick in More → Appearance.
 * Each style is three seed colours (+ a background tint); light and dark schemes are derived from them.
 * "wallpaper" is special: Android 12+ builds it from the phone's wallpaper (handled in Theme.kt).
 */
data class Palette(
    val id: String,
    val name: String,
    val mood: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    /** Hue that tints backgrounds and cards. */
    val tint: Color = primary,
)

object Palettes {
    val WALLPAPER = Palette("wallpaper", "Wallpaper", "Matches your phone", Color(0xFF4F6BED), Color(0xFF6A7390), Color(0xFF8A5A9E))

    val all: List<Palette> = listOf(
        WALLPAPER,
        Palette("ocean", "Ocean", "Calm", Color(0xFF0B74C4), Color(0xFF00A6A6), Color(0xFF5C6BC0)),
        Palette("forest", "Forest", "Grounded", Color(0xFF2E7D32), Color(0xFF7CB342), Color(0xFF8D6E63)),
        Palette("sunset", "Sunset", "Warm", Color(0xFFE65100), Color(0xFFD81B60), Color(0xFFFFB300)),
        Palette("berry", "Berry", "Playful", Color(0xFFAD1457), Color(0xFF7B1FA2), Color(0xFFEC407A)),
        Palette("lavender", "Lavender", "Relaxed", Color(0xFF7E57C2), Color(0xFF9575CD), Color(0xFF4DB6AC)),
        Palette("ember", "Ember", "Fired up", Color(0xFFC62828), Color(0xFFFF7043), Color(0xFFFFCA28)),
        Palette("mint", "Mint", "Fresh", Color(0xFF00897B), Color(0xFF26C6DA), Color(0xFF9CCC65)),
        Palette("gold", "Gold", "Confident", Color(0xFFB8860B), Color(0xFF8D6E63), Color(0xFF26A69A)),
        Palette("neon", "Neon", "Energetic", Color(0xFF00C853), Color(0xFF00B8D4), Color(0xFFD500F9), tint = Color(0xFF00B8D4)),
        Palette("slate", "Slate", "Focused", Color(0xFF455A64), Color(0xFF78909C), Color(0xFF5C6BC0)),
        Palette("cherry", "Cherry Blossom", "Gentle", Color(0xFFD87093), Color(0xFFB39DDB), Color(0xFF81C784)),
        Palette("midnight", "Midnight", "Night owl", Color(0xFF3949AB), Color(0xFF00ACC1), Color(0xFFAB47BC), tint = Color(0xFF1A237E)),
        Palette("desert", "Desert", "Easygoing", Color(0xFFBF6F3A), Color(0xFFA1887F), Color(0xFF4E9A8E)),
        Palette("mono", "Mono", "Minimal", Color(0xFF424242), Color(0xFF757575), Color(0xFF9E9E9E), tint = Color(0xFF9E9E9E)),
    )

    fun byId(id: String): Palette = all.firstOrNull { it.id == id } ?: WALLPAPER
}

private fun Color.mix(other: Color, t: Float) = lerp(this, other, t)

/** Black or white, whichever reads better on [bg]. */
private fun onColor(bg: Color): Color = if (bg.luminance() > 0.4f) Color(0xFF111111) else Color.White

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

fun Palette.lightScheme(): ColorScheme {
    val white = Color.White
    val bg = white.mix(tint, 0.04f)
    fun container(c: Color) = white.mix(c, 0.22f)
    fun onContainer(c: Color) = c.mix(Color.Black, 0.55f)
    return lightColorScheme(
        primary = primary, onPrimary = onColor(primary),
        primaryContainer = container(primary), onPrimaryContainer = onContainer(primary),
        secondary = secondary, onSecondary = onColor(secondary),
        secondaryContainer = container(secondary), onSecondaryContainer = onContainer(secondary),
        tertiary = tertiary, onTertiary = onColor(tertiary),
        tertiaryContainer = container(tertiary), onTertiaryContainer = onContainer(tertiary),
        background = bg, onBackground = Color(0xFF1A1C1E),
        surface = bg, onSurface = Color(0xFF1A1C1E),
        surfaceVariant = white.mix(tint, 0.12f), onSurfaceVariant = Color(0xFF44474E),
        surfaceContainerLowest = white,
        surfaceContainerLow = white.mix(tint, 0.05f),
        surfaceContainer = white.mix(tint, 0.08f),
        surfaceContainerHigh = white.mix(tint, 0.11f),
        surfaceContainerHighest = white.mix(tint, 0.14f),
        surfaceTint = primary,
        outline = Color(0xFF74777F).mix(tint, 0.2f), outlineVariant = white.mix(tint, 0.25f).mix(Color.Gray, 0.3f),
    )
}

fun Palette.darkScheme(amoled: Boolean): ColorScheme {
    val base = if (amoled) Color.Black else Color(0xFF121316).mix(tint, 0.06f)
    // Light, soft versions of the seeds read better on dark backgrounds.
    val p = primary.mix(Color.White, 0.35f)
    val s = secondary.mix(Color.White, 0.35f)
    val t = tertiary.mix(Color.White, 0.35f)
    fun container(c: Color) = c.mix(Color.Black, 0.55f)
    fun onContainer(c: Color) = c.mix(Color.White, 0.75f)
    fun level(x: Float) = if (amoled) Color.Black.mix(tint, x * 0.7f) else base.mix(tint, x)
    return darkColorScheme(
        primary = p, onPrimary = onColor(p),
        primaryContainer = container(primary), onPrimaryContainer = onContainer(primary),
        secondary = s, onSecondary = onColor(s),
        secondaryContainer = container(secondary), onSecondaryContainer = onContainer(secondary),
        tertiary = t, onTertiary = onColor(t),
        tertiaryContainer = container(tertiary), onTertiaryContainer = onContainer(tertiary),
        background = base, onBackground = Color(0xFFE3E2E6),
        surface = base, onSurface = Color(0xFFE3E2E6),
        surfaceVariant = level(0.16f), onSurfaceVariant = Color(0xFFC4C6D0),
        surfaceContainerLowest = if (amoled) Color.Black else base.mix(Color.Black, 0.3f),
        surfaceContainerLow = level(0.06f),
        surfaceContainer = level(0.09f),
        surfaceContainerHigh = level(0.12f),
        surfaceContainerHighest = level(0.15f),
        surfaceTint = p,
        outline = Color(0xFF8E9099).mix(tint, 0.2f), outlineVariant = level(0.25f),
    )
}
