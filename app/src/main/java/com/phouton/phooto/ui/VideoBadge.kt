package com.phouton.phooto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val BadgeShape = RoundedCornerShape(6.dp)

/**
 * Tiny ▶ badge shown in the bottom-right corner of video tiles.
 * Purely informational — the tile itself is clickable and opens the viewer.
 */
@Composable
fun VideoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(BadgeShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Video",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(12.dp),
        )
    }
}
