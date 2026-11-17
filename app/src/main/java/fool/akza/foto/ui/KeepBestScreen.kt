package fool.akza.foto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.similar.DuplicateGroup
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

@Composable
internal fun KeepBestScreen(
    group: DuplicateGroup,
    onBack: () -> Unit,
    onKeepBest: (MediaItem) -> Unit,
    onView: (MediaItem, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var keeper by remember(group.id) { mutableStateOf(group.keeper) }
    var peekItem by remember { mutableStateOf<MediaItem?>(null) }

    val removableCount = group.items.count { it.uri != keeper.uri }
    val totalBytes = group.items.sumOf { it.sizeBytes }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Frosted-style header (custom 56dp row; SettingsHeader has no trailing slot).
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
                    contentDescription = null,
                    tint = TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Keep the best",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
        }

        // Context line.
        Text(
            text = "${group.items.size} copies · ${formatBytes(totalBytes)} · $removableCount can be removed",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = "Tap a photo to keep it · tap the corner icon to view full size.",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        // Grid.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .animateContentSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(group.items, key = { it.uri.toString() }) { item ->
                val isKeeper = item.uri == keeper.uri
                var tileRect by remember { mutableStateOf(Rect.Zero) }
                val tileAlpha by animateFloatAsState(
                    targetValue = if (isKeeper) 1f else 0.62f,
                    animationSpec = tween(180),
                    label = "keeperAlpha",
                )
                val tileScale by animateFloatAsState(
                    targetValue = if (isKeeper) 1f else 0.985f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "keeperScale",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (isKeeper) {
                        TextPrimary.copy(alpha = 0.8f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(180),
                    label = "keeperBorder",
                )
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .onGloballyPositioned { coordinates ->
                            tileRect = coordinates.boundsInWindow()
                        }
                        .graphicsLayer {
                            scaleX = tileScale
                            scaleY = tileScale
                            alpha = tileAlpha
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                        // Tap selects the keeper; holding peeks without changing it.
                        .peekGesture(
                            onPeekStart = { peekItem = item },
                            onPeekEnd = { peekItem = null },
                            onClick = { keeper = item },
                        ),
                ) {
                    Thumb(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AnimatedContent(
                        targetState = isKeeper,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 0.8f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(targetScale = 0.8f))
                        },
                        label = "keeperBadge",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) { keeperNow ->
                        if (keeperNow) {
                            Surface(
                                shape = CircleShape,
                                color = TextPrimary,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = AmoledBlack,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = "Keep",
                                        color = AmoledBlack,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = DeleteRed,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Viewing full size is a separate affordance; it never
                    // changes which photo is the keeper. The 44dp target holds
                    // the 28dp visual circle, so it is easy to hit.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(44.dp)
                            .clickable { onView(item, tileRect) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AmoledBlack.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.OpenInFull,
                                contentDescription = "View full size",
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AmoledBlack.copy(alpha = 0.62f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = formatBytes(item.sizeBytes),
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // Recommendation block.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Keep this copy and move the other $removableCount to the trash.",
                color = TextMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Primary button.
        Surface(
            onClick = { onKeepBest(keeper) },
            shape = RoundedCornerShape(12.dp),
            color = TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = removableCount,
                    transitionSpec = {
                        fadeIn(tween(150)) togetherWith fadeOut(tween(120))
                    },
                    label = "keepCount",
                ) { count ->
                    Text(
                        text = "Keep best, trash $count",
                        color = AmoledBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
    PhotoPeekOverlay(peekItem)
    }
}

private fun formatBytes(bytes: Long): String {
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
