package fool.akza.foto.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.attention.AttentionEntry
import fool.akza.foto.data.attention.AttentionLens
import fool.akza.foto.data.attention.AttentionPeriod
import fool.akza.foto.data.attention.AttentionRepository
import fool.akza.foto.data.attention.AttentionTile
import fool.akza.foto.data.attention.rankAttention
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

private val CardShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(12.dp)

/** Upper bound on composed leaderboard rows; the summary counts all of them. */
private const val MaxRenderedRows = 60

/**
 * Ranked viewing time ("Insights"): the photos and videos the viewer spent the
 * most time with. Opened from the smart tile on the Albums grid; tapping a row
 * opens the viewer on that ranked list so the user can keep swiping through the
 * "most watched" items.
 */
@Composable
internal fun InsightsScreen(
    onBack: () -> Unit,
    onOpen: (List<MediaItem>, Int) -> Unit,
) {
    val context = LocalContext.current
    val version by AttentionRepository.version.collectAsState()
    var period by remember { mutableStateOf(AttentionPeriod.WEEK) }
    var lens by remember { mutableStateOf(AttentionLens.MOST_WATCHED) }
    var entries by remember { mutableStateOf<List<AttentionEntry>?>(null) }

    LaunchedEffect(period, version) {
        entries = AttentionRepository.entries(context, period)
    }

    BackHandler { onBack() }

    val ranked = remember(entries, lens) { entries?.let { rankAttention(it, lens) } }
    // Render a bounded leaderboard; the summary still counts every item.
    val visible = remember(ranked) { ranked?.take(MaxRenderedRows) }
    val maxTotal = remember(visible) {
        visible?.maxOfOrNull { it.totalMs }?.coerceAtLeast(1L) ?: 1L
    }

    FrostedHeaderScaffold(
        background = AmoledBlack,
        header = { SettingsHeader(title = stringResource(R.string.insights_title), onBack = onBack) },
    ) { listModifier ->
        LazyColumn(
            modifier = listModifier.navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = frostedContentTopPadding(20.dp),
                end = 20.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(entries) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttentionPeriod.entries.forEach { option ->
                        ChoiceChip(
                            text = periodLabel(option),
                            selected = option == period,
                            onClick = { period = option },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AttentionLens.entries.forEach { option ->
                        ChoiceChip(
                            text = lensLabel(option),
                            selected = option == lens,
                            onClick = { lens = option },
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.insights_legend),
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
                )
            }

            when {
                visible == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary,
                            trackColor = Border,
                        )
                    }
                }
                visible.isEmpty() -> item { EmptyInsights() }
                else -> item {
                    SettingsGroup {
                        visible.forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider()
                            InsightRow(
                                rank = index + 1,
                                entry = entry,
                                maxTotalMs = maxTotal,
                                onClick = { onOpen(visible.map { it.item }, index) },
                            )
                        }
                    }
                }
            }

            item { PrivacyNote() }
        }
    }
}

/**
 * Square smart tile shown as the first item of the Albums grid. Uses the
 * most-watched item as a dimmed backdrop with a chart glyph, and names the tile
 * "Insights"; the caption counts the items with recorded viewing time.
 */
@Composable
internal fun InsightsTile(
    tile: AttentionTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.insights_title)
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
            val preview = tile.preview
            // Heavily blurred, not dimmed: the tile hints at what you watch
            // without showing it. RenderEffect blur needs API 31+; on older
            // devices leave the tile plain rather than revealing the photo.
            if (preview != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Thumb(
                    item = preview,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 28.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .graphicsLayer {
                            // Scale past the tile so the unbounded blur never
                            // shows transparent edges inside the clip.
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
                    imageVector = Icons.Outlined.BarChart,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp),
                )
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
            text = pluralStringResource(R.plurals.item_count, tile.items, tile.items),
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SummaryCard(entries: List<AttentionEntry>?) {
    val totalMs = entries?.sumOf { it.totalMs } ?: 0L
    val watches = entries?.sumOf { it.watches } ?: 0
    val items = entries?.size ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = ThemeSurface,
        border = BorderStroke(1.dp, Border),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.insights_summary_label),
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatWatchTime(totalMs),
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.insights_summary_meta,
                    pluralStringResource(R.plurals.item_count, items, items),
                    pluralStringResource(R.plurals.insights_watch_count, watches, watches),
                ),
                color = TextMuted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun InsightRow(
    rank: Int,
    entry: AttentionEntry,
    maxTotalMs: Long,
    onClick: () -> Unit,
) {
    val item = entry.item
    val fraction = (entry.totalMs.toFloat() / maxTotalMs).coerceIn(0.02f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AmoledBlack),
        ) {
            Thumb(item = item, modifier = Modifier.fillMaxSize())
            if (item.isVideo) {
                VideoBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName
                    ?: stringResource(if (item.isVideo) R.string.video_badge else R.string.media_photo),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Border),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(TextPrimary.copy(alpha = 0.75f)),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(
                    R.string.insights_row_meta,
                    formatWatchTime(entry.totalMs),
                    pluralStringResource(
                        R.plurals.insights_watch_count,
                        entry.watches,
                        entry.watches,
                    ),
                ),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyInsights() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.BarChart,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.insights_empty_title),
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.insights_empty_body),
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        text = stringResource(R.string.insights_privacy_note),
        color = TextMuted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 8.dp, end = 8.dp),
    )
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) TextPrimary else ThemeSurface)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else Border,
                shape = CircleShape,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = if (selected) AmoledBlack else TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun periodLabel(period: AttentionPeriod): String = stringResource(
    when (period) {
        AttentionPeriod.WEEK -> R.string.insights_period_week
        AttentionPeriod.MONTH -> R.string.insights_period_month
        AttentionPeriod.ALL -> R.string.insights_period_all
    },
)

@Composable
private fun lensLabel(lens: AttentionLens): String = stringResource(
    when (lens) {
        AttentionLens.MOST_WATCHED -> R.string.insights_lens_watched
        AttentionLens.LONGEST_LOOK -> R.string.insights_lens_longest
        AttentionLens.REVISITED -> R.string.insights_lens_revisited
    },
)
