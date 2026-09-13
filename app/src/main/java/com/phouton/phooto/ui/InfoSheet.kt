package com.phouton.phooto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.data.MediaInfo
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.MediaRepository
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Surface as SurfaceColor
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary

/** Bottom sheet with name, type, size, dimensions, dates and album. */
@Composable
internal fun InfoSheet(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<MediaInfo?>(null) }

    LaunchedEffect(item) {
        info = MediaRepository.loadInfo(context, item)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.6f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceColor,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Info", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val d = info
                InfoRow("Name", d?.name ?: "…")
                InfoRow("Type", typeLabel(item, d))
                InfoRow("Size", d?.let { formatSize(it.sizeBytes) } ?: "…")
                val w = d?.width ?: 0
                val h = d?.height ?: 0
                if (w > 0 && h > 0) InfoRow("Dimensions", "$w × $h")
                if (item.bucketName.isNotBlank()) InfoRow("Album", item.bucketName)
                InfoRow("Date added", d?.let { formatDate(it.dateAdded) } ?: "…")
                val modified = d?.dateModified ?: 0L
                if (modified > 0L) InfoRow("Date modified", formatDate(modified))
            }
        }
    }
}

/** Human-readable kind: the MIME type when known, else Image/Video. */
private fun typeLabel(item: MediaItem, info: MediaInfo?): String {
    val mime = info?.mimeType ?: item.mimeType
    return mime ?: if (item.isVideo) "Video" else "Image"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontSize = 14.sp)
    }
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatDate(epochSeconds: Long): String {
    val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return date.format(java.util.Date(epochSeconds * 1000))
}
