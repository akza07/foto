package com.phouton.phooto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.phouton.phooto.ui.theme.EmptySurface
import com.phouton.phooto.ui.theme.TextMuted

/**
 * Distinct "no preview" placeholder for tiles and viewer pages whose media can't
 * be shown (missing file, decode failure, or still loading). Uses a lifted
 * zinc-900 surface with a broken-image glyph instead of the pure-black AMOLED
 * background, so an empty preview reads as an empty state rather than a black
 * gap. Rounded corners match the app's 12dp tile shape; pass `radius = 0` for
 * full-screen surfaces (the viewer page).
 */
@Composable
fun EmptyPreview(modifier: Modifier = Modifier, radius: Int = 12) {
    val shape = RoundedCornerShape(radius.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(EmptySurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.fillMaxSize(0.32f),
        )
    }
}
