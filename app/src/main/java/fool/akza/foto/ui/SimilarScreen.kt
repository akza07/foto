package fool.akza.foto.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.similar.DuplicateGroup
import fool.akza.foto.data.similar.PossibleGroup
import fool.akza.foto.data.similar.SimilarGroup
import fool.akza.foto.data.similar.SimilarReport
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import java.util.Locale

private val TileShape = RoundedCornerShape(12.dp)
private val ThumbShape = RoundedCornerShape(10.dp)

@Composable
internal fun SimilarTile(
    report: SimilarReport,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "Similar"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(TileShape)
                .background(ThemeSurface),
            contentAlignment = Alignment.Center,
        ) {
            val preview = report.preview
            if (preview != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Thumb(
                    item = preview,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 28.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .graphicsLayer {
                            scaleX = 1.2f
                            scaleY = 1.2f
                        },
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AmoledBlack.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            if (report.recommendations > 0) {
                Surface(
                    shape = CircleShape,
                    color = TextPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = report.recommendations.toString(),
                            color = AmoledBlack,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${report.recommendations} to review",
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SimilarScreen(
    report: SimilarReport?,
    isAnalyzing: Boolean,
    onBack: () -> Unit,
    onReviewDuplicates: (DuplicateGroup) -> Unit,
    onOpenGroup: (SimilarGroup) -> Unit,
    onReviewPossible: (PossibleGroup) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember(report) {
        buildList {
            report?.duplicates?.takeIf { it.isNotEmpty() }?.let {
                add(SimilarTab(SimilarSection.DUPLICATES, "Duplicates", it.size))
            }
            report?.similarGroups?.takeIf { it.isNotEmpty() }?.let {
                add(SimilarTab(SimilarSection.GROUPS, "Groups", it.size))
            }
            report?.possibleGroups?.takeIf { it.isNotEmpty() }?.let {
                add(SimilarTab(SimilarSection.POSSIBLE, "Other", it.size))
            }
        }
    }
    var selected by remember(report) { mutableStateOf(0) }
    LaunchedEffect(tabs.size) {
        if (selected >= tabs.size) selected = 0
    }

    FrostedHeaderScaffold(
        background = AmoledBlack,
        header = { SettingsHeader(title = "Similar", onBack = onBack) },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = frostedContentTopPadding(20.dp)),
        ) {
            when {
                report == null && isAnalyzing -> SimilarLoading()
                report == null || report.isEmpty -> SimilarEmpty()
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${report.recommendations} groups to review \u00b7 " +
                                "${formatBytes(report.bytesReclaimable)} reclaimable",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Scan again",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onRescan,
                            ),
                        )
                    }
                    if (tabs.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        SimilarTabs(
                            tabs = tabs,
                            selected = selected,
                            onSelect = { selected = it },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "similar-tab",
                        modifier = Modifier.weight(1f),
                    ) { index ->
                        val tab = tabs.getOrNull(index)
                        if (tab == null) {
                            SimilarEmpty()
                        } else {
                            SimilarTabList(
                                tab = tab,
                                report = report,
                                onReviewDuplicates = onReviewDuplicates,
                                onOpenGroup = onOpenGroup,
                                onReviewPossible = onReviewPossible,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarTabList(
    tab: SimilarTab,
    report: SimilarReport,
    onReviewDuplicates: (DuplicateGroup) -> Unit,
    onOpenGroup: (SimilarGroup) -> Unit,
    onReviewPossible: (PossibleGroup) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
    ) {
        when (tab.section) {
            SimilarSection.DUPLICATES -> itemsIndexed(
                report.duplicates,
                key = { _, group -> group.id },
            ) { index, group ->
                SimilarRow(
                    thumbs = group.items.take(3),
                    title = "${group.items.size} copies of ${group.keeper.displayName ?: "photo"}",
                    subtitle = "Keep one \u00b7 frees ${formatBytes(group.bytesFreed)}",
                    actionLabel = "Review",
                    primary = false,
                    onClick = { onReviewDuplicates(group) },
                )
                if (index < report.duplicates.lastIndex) SimilarDivider()
            }

            SimilarSection.GROUPS -> itemsIndexed(
                report.similarGroups,
                key = { _, group -> group.id },
            ) { index, group ->
                SimilarRow(
                    thumbs = group.items.take(3),
                    title = "${group.items.size} similar shots",
                    subtitle = "${group.suggestedName} in this folder",
                    actionLabel = null,
                    primary = false,
                    onClick = { onOpenGroup(group) },
                )
                if (index < report.similarGroups.lastIndex) SimilarDivider()
            }

            SimilarSection.POSSIBLE -> itemsIndexed(
                report.possibleGroups,
                key = { _, group -> group.id },
            ) { index, group ->
                SimilarRow(
                    thumbs = group.items.take(3),
                    title = "${group.items.size} ungrouped shots",
                    subtitle = "${(group.averageSimilarity * 100).toInt()}% match \u00b7 not grouped",
                    actionLabel = "Review",
                    primary = false,
                    onClick = { onReviewPossible(group) },
                )
                if (index < report.possibleGroups.lastIndex) SimilarDivider()
            }
        }
    }
}

@Composable
private fun SimilarTabs(
    tabs: List<SimilarTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ThemeSurface)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) TextPrimary else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${tab.label} (${tab.count})",
                    color = if (active) AmoledBlack else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SimilarDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        thickness = 1.dp,
        color = Border.copy(alpha = 0.6f),
    )
}

@Composable
private fun SimilarLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(text = "Looking for similar photos", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SimilarEmpty() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Nothing to clean up", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Photos that look alike will appear here.",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

private enum class SimilarSection { DUPLICATES, GROUPS, POSSIBLE }

private data class SimilarTab(val section: SimilarSection, val label: String, val count: Int)

@Composable
private fun SimilarRow(
    thumbs: List<MediaItem>,
    title: String,
    subtitle: String,
    actionLabel: String?,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlappingThumbs(items = thumbs)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (actionLabel != null) {
            ActionPill(text = actionLabel, primary = primary, onClick = onClick)
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun OverlappingThumbs(items: List<MediaItem>) {
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(44.dp),
    ) {
        items.take(3).forEachIndexed { index, item ->
            Thumb(
                item = item,
                modifier = Modifier
                    .offset(x = (index * 10).dp)
                    .size(44.dp)
                    .clip(ThumbShape)
                    .border(1.dp, Border, ThumbShape)
                    .zIndex(index.toFloat()),
            )
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (primary) TextPrimary else ThemeSurface,
        border = if (!primary) BorderStroke(1.dp, Border) else null,
    ) {
        Text(
            text = text,
            color = if (primary) AmoledBlack else TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}