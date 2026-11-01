package fool.akza.foto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaInfo
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.MediaRepository
import fool.akza.foto.data.MetadataLabel
import fool.akza.foto.data.MetadataValue
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as SurfaceColor
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/**
 * Bottom sheet with everything the file knows about itself: name, type, size,
 * dimensions, album and dates, then the embedded EXIF/container metadata as
 * titled blocks (image or video details, camera, exposure, location, tags and
 * a verbatim tail of the remaining tags).
 */
@Composable
internal fun InfoSheet(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var info by remember(item) { mutableStateOf<MediaInfo?>(null) }

    // Back closes the sheet, not the viewer underneath it.
    BackHandler { onClose() }

    // Back closes the sheet, not the viewer underneath it.
    BackHandler { onClose() }

    LaunchedEffect(item) {
        info = MediaRepository.loadInfo(context, item)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Tall metadata lists must not push the sheet past the window; the
        // content scrolls inside instead.
        val sheetMaxHeight = maxHeight * 0.85f

        // Scrim: tap outside closes.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack.copy(alpha = 0.6f))
                .clickable(onClick = onClose),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = sheetMaxHeight)
                // Swallow taps on the sheet so reading a row doesn't dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceColor,
            border = BorderStroke(1.dp, Border),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InfoHeader(onClose = onClose)

                val loaded = info
                val sections = loaded?.metadata.orEmpty()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    item(key = "file-header") {
                        SectionHeader(stringResource(R.string.info_section_file), first = true)
                    }
                    item(key = "file") {
                        Column {
                            InfoRow(stringResource(R.string.info_name), loaded?.name ?: "…")
                            InfoRow(stringResource(R.string.info_type), typeLabel(item, loaded))
                            InfoRow(
                                stringResource(R.string.info_size),
                                loaded?.let { formatFileSize(it.sizeBytes) } ?: "…",
                            )
                            val w = loaded?.width ?: 0
                            val h = loaded?.height ?: 0
                            if (w > 0 && h > 0) {
                                InfoRow(stringResource(R.string.info_dimensions), "$w × $h")
                            }
                            val path = item.relativePath?.takeIf { it.isNotBlank() }
                            if (path != null) {
                                InfoRow(stringResource(R.string.info_path), path)
                            }
                            if (item.bucketName.isNotBlank()) {
                                InfoRow(stringResource(R.string.info_album), item.bucketName)
                            }
                            InfoRow(
                                stringResource(R.string.info_date_added),
                                loaded?.let { formatDate(it.dateAdded) } ?: "…",
                            )
                            val modified = loaded?.dateModified ?: 0L
                            if (modified > 0L) {
                                InfoRow(stringResource(R.string.info_date_modified), formatDate(modified))
                            }
                        }
                    }

                    sections.forEachIndexed { index, section ->
                        item(key = "section-$index") {
                            SectionHeader(stringResource(section.group.titleRes))
                        }
                        itemsIndexed(section.fields, key = { row, _ -> "row-$index-$row" }) { _, field ->
                            InfoRow(labelText(field.label), valueText(field.value))
                        }
                    }

                    if (loaded != null && sections.isEmpty()) {
                        item(key = "no-metadata") {
                            Text(
                                text = stringResource(R.string.info_no_metadata),
                                color = TextMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.info_title),
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.info_close),
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Section label: 12sp SemiBold muted, 0.8sp tracking, per DESIGN §3.2. */
@Composable
private fun SectionHeader(title: String, first: Boolean = false) {
    Text(
        text = title,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = if (first) 6.dp else 18.dp, bottom = 2.dp),
    )
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
private fun labelText(label: MetadataLabel): String = when (label) {
    is MetadataLabel.Res -> stringResource(label.id)
    is MetadataLabel.Tag -> label.name
}

@Composable
private fun valueText(value: MetadataValue): String = when (value) {
    is MetadataValue.Text -> value.text
    is MetadataValue.Res -> stringResource(value.id)
}

/**
 * Label left, value right. Both halves wrap, so a long lens name or a path
 * never truncates the value.
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .weight(0.46f)
                .padding(end = 12.dp),
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.54f),
        )
    }
}

private fun formatDate(epochSeconds: Long): String {
    val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return date.format(java.util.Date(epochSeconds * 1000))
}
