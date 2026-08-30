package com.phouton.phooto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pitch-black AMOLED background with shadcn/zinc neutrals.
val AmoledBlack = Color(0xFF000000)
val Surface = Color(0xFF09090B)
val Border = Color(0xFF27272A)
val TextPrimary = Color(0xFFFAFAFA)
val TextMuted = Color(0xFFA1A1AA)
// Lifted zinc-900 surface for tiles/viewer pages that have no preview to show,
// so an empty state can never be mistaken for the pure-black background.
val EmptySurface = Color(0xFF18181B)

private val PhootoDarkColorScheme = darkColorScheme(
    primary = TextPrimary,
    onPrimary = Surface,
    secondary = TextMuted,
    onSecondary = TextPrimary,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextMuted,
    outline = Border,
    outlineVariant = Border,
)

@Composable
fun PhootoTheme(content: @Composable () -> Unit) {
    // This app is AMOLED-only; ignore system theme and always use the dark palette.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = PhootoDarkColorScheme,
        content = content,
    )
}
