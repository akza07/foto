package fool.akza.foto.ui

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaInfo
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.MediaRepository
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as SurfaceColor
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/** Bottom sheet with name, type, size, dimensions, dates and album. */
@Composable
internal fun InfoSheet(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<MediaInfo?>(null) }

    // Back closes the sheet, not the viewer underneath it.
    BackHandler { onClose() }

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
            border = BorderStroke(1.dp, Border),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.info_title), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val d = info
                InfoRow(stringResource(R.string.info_name), d?.name ?: "…")
                InfoRow(stringResource(R.string.info_type), typeLabel(item, d))
                InfoRow(stringResource(R.string.info_size), d?.let { formatFileSize(it.sizeBytes) } ?: "…")
                val w = d?.width ?: 0
                val h = d?.height ?: 0
                if (w > 0 && h > 0) InfoRow(stringResource(R.string.info_dimensions), "$w × $h")
                if (item.bucketName.isNotBlank()) InfoRow(stringResource(R.string.info_album), item.bucketName)
                InfoRow(stringResource(R.string.info_date_added), d?.let { formatDate(it.dateAdded) } ?: "…")
                val modified = d?.dateModified ?: 0L
                if (modified > 0L) InfoRow(stringResource(R.string.info_date_modified), formatDate(modified))
            }
        }
    }
}

/** Human-readable kind: the MIME type when known, else Image/Video. */
@Composable
private fun typeLabel(item: MediaItem, info: MediaInfo?): String {
    val mime = info?.mimeType ?: item.mimeType
    return mime ?: stringResource(
        if (item.isVideo) R.string.info_kind_video else R.string.info_kind_image,
    )
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

private fun formatDate(epochSeconds: Long): String {
    val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return date.format(java.util.Date(epochSeconds * 1000))
}
