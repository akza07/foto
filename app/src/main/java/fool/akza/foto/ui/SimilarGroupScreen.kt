package fool.akza.foto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.similar.SimilarGroup
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/**
 * The list of photos in one similar group. Tapping a photo opens the viewer;
 * the folder action is the only way into the folder sheet, so entering this
 * screen never throws the user into a modal.
 */
@Composable
internal fun SimilarGroupScreen(
    group: SimilarGroup,
    onBack: () -> Unit,
    onOpenItem: (List<MediaItem>, Int, Rect) -> Unit,
    onGroupFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var peekItem by remember { mutableStateOf<MediaItem?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Similar shots",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.items.size} photos · ${folderName(group.folder)}",
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = "Tap a photo to view it full size.",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(group.items, key = { _, item -> item.uri.toString() }) { index, item ->
                var tileRect by remember { mutableStateOf(Rect.Zero) }
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .onGloballyPositioned { coordinates ->
                            tileRect = coordinates.boundsInWindow()
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .peekGesture(
                            onPeekStart = { peekItem = item },
                            onPeekEnd = { peekItem = null },
                            onClick = { onOpenItem(group.items, index, tileRect) },
                        ),
                ) {
                    Thumb(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (index == 0) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AmoledBlack.copy(alpha = 0.62f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                        ) {
                            Text(
                                text = "Cover",
                                color = TextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Suggested folder · ${group.suggestedName}",
                color = TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = onGroupFolder,
                shape = RoundedCornerShape(12.dp),
                color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = AmoledBlack,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Group into folder",
                        color = AmoledBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        }
        PhotoPeekOverlay(peekItem)
    }
}

private fun folderName(folder: String?): String =
    folder?.trim('/')?.substringAfterLast('/')?.ifBlank { null } ?: "Library"
