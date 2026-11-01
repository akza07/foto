package fool.akza.foto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.MetadataIndexer
import fool.akza.foto.data.MetadataStore
import fool.akza.foto.data.matchesAllTokens
import fool.akza.foto.data.tokenizeForSearch
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

private val SearchGridSpacing = 8.dp

/** Quiet period before a metadata query runs, so per-keystroke scans can't queue. */
private const val SearchDebounceMs = 150L

/** Quiet progress line: search results improve while the background pass runs. */
@Composable
private fun IndexingNotice(remaining: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = TextMuted,
            trackColor = Border,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.search_indexing_notice, remaining, total),
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/** One search result tile; metadata-only hits are marked so the match is clear. */
@Composable
private fun SearchHit(
    item: MediaItem,
    metadataOnly: Boolean,
    modifier: Modifier = Modifier,
    results: List<MediaItem>,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
) {
    Box {
        ImageTile(
            item = item,
            modifier = modifier,
            onTilePositioned = onTilePositioned,
            onTileDisposed = onTileDisposed,
            onClick = { rect ->
                keyboard?.hide()
                onItemClick(results, results.indexOf(item), rect)
            },
        )
        if (metadataOnly) {
            // A quiet marker instead of a second row of text: the tile keeps its
            // 12dp corner language and nothing covers the photo.
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .background(AmoledBlack.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.search_metadata_badge),
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Month/year bucket label for a `dateAdded` (epoch seconds). */
private fun monthLabel(epochSeconds: Long, unknown: String): String {
    if (epochSeconds <= 0L) return unknown
    val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochSeconds * 1000L))
}

/**
 * Flat search over the whole library. [items] is the full set of loaded media
 * (MediaStore + SAF-browsed), so results include content inside `.nomedia`
 * folders that MediaStore alone would miss.
 *
 * A hit is either a file name match or a metadata match: the background index
 * (see `MetadataIndexer`) stores each file's EXIF/PNG text, so queries like a
 * camera model, a lens, "ISO 400", a place or a PNG keyword find photos whose
 * names say nothing. Name hits keep the month grouping; metadata-only hits are
 * listed under their own header so it is obvious why they matched.
 */
@Composable
fun SearchScreen(
    items: List<MediaItem>,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    minTileSize: Dp = 112.dp,
    onTilePositioned: (Uri, Rect) -> Unit = { _, _ -> },
    onTileDisposed: (Uri) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val context = LocalContext.current
    var metadataHits by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Indexing is background work; surface it and let results fill in as files
    // are read rather than making the user retype the query later.
    val indexProgress by MetadataIndexer.progress.collectAsState()
    val indexGeneration by MetadataIndexer.generation.collectAsState()

    LaunchedEffect(query, indexGeneration) {
        val q = query.trim()
        if (q.isEmpty()) {
            metadataHits = emptySet()
            return@LaunchedEffect
        }
        // Typing (or a batch of newly indexed files) can outpace a lookup; a
        // short pause keeps one query in flight instead of a queue of them.
        delay(SearchDebounceMs)
        metadataHits = MetadataStore.search(context, q)
    }

    val trimmedQuery = query.trim()
    val queryTokens = remember(trimmedQuery) { tokenizeForSearch(trimmedQuery) }
    val nameResults = remember(items, queryTokens) {
        if (queryTokens.isEmpty()) {
            emptyList()
        } else {
            // Same forgiving rules as the metadata index: any word order,
            // prefixes and small typos, so "lighthosue" still finds
            // "Lighthouse-at-dusk.png".
            items.filter { item ->
                item.displayName?.let { matchesAllTokens(queryTokens, tokenizeForSearch(it)) } == true
            }
        }
    }
    val metadataOnlyResults = remember(items, metadataHits, nameResults) {
        if (metadataHits.isEmpty()) {
            emptyList()
        } else {
            val named = nameResults.mapTo(HashSet()) { it.uri.toString() }
            items
                .filter { it.uri.toString() in metadataHits && it.uri.toString() !in named }
                .sortedByDescending { it.dateAdded }
        }
    }
    // Combined list: the viewer pages through everything the query found.
    val results = nameResults + metadataOnlyResults
    val unknownDateLabel = stringResource(R.string.search_unknown_date)

    FrostedHeaderScaffold(
        background = AmoledBlack,
        header = {
            // Header: back arrow + inline query field.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(AppBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextPrimary,
                )
            }
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = TextMuted,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The placeholder fades out as the first character lands instead
                // of vanishing in one frame.
                val placeholderAlpha by animateFloatAsState(
                    targetValue = if (query.isEmpty()) 1f else 0f,
                    animationSpec = tween(120),
                    label = "search-placeholder",
                )
                Text(
                    text = stringResource(R.string.search_placeholder),
                    color = TextMuted,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(placeholderAlpha),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120)),
            ) {
                IconButton(onClick = { query = "" }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = TextMuted,
                    )
                }
            }
            }
        },
    ) { contentModifier ->
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val state = when {
            query.isBlank() -> SearchState.Hint
            results.isEmpty() -> SearchState.Empty
            else -> SearchState.Results
        }
        // Typing swaps three different layouts (hint, empty state, results).
        // A short crossfade keeps that from reading as a hard cut; the grid
        // itself animates its own items, so a query that stays in Results does
        // not crossfade.
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "search-state",
        ) { state ->
        when (state) {
            SearchState.Hint -> {
                Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
            SearchState.Empty -> {
                Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = if (indexProgress.isIndexing) {
                            stringResource(R.string.search_no_results_indexing, indexProgress.remaining)
                        } else {
                            stringResource(R.string.search_no_results)
                        },
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
            SearchState.Results -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minTileSize),
                    modifier = contentModifier.navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = frostedContentTopPadding(12.dp),
                        end = 12.dp,
                        bottom = 12.dp + bottomInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SearchGridSpacing),
                    verticalArrangement = Arrangement.spacedBy(SearchGridSpacing),
                ) {
                    item(
                        key = "search-scope",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "caption",
                    ) {
                        Text(
                            text = stringResource(R.string.search_scope),
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    if (indexProgress.isIndexing) {
                        item(
                            key = "search-indexing",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "notice",
                        ) {
                            IndexingNotice(indexProgress.remaining, indexProgress.total)
                        }
                    }
                    // Name hits first, grouped by month so long result sets
                    // stay navigable.
                    nameResults
                        .groupBy { monthLabel(it.dateAdded, unknownDateLabel) }
                        .forEach { (label, group) ->
                            item(
                                key = "header:name:$label",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "month",
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            items(group, key = { "search:${it.uri}" }) { item ->
                                SearchHit(
                                    item = item,
                                    metadataOnly = false,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(180),
                                        fadeOutSpec = tween(140),
                                        placementSpec = spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ),
                                    results = results,
                                    keyboard = keyboard,
                                    onItemClick = onItemClick,
                                    onTilePositioned = onTilePositioned,
                                    onTileDisposed = onTileDisposed,
                                )
                            }
                        }

                    // Files whose name doesn't match but whose metadata does.
                    if (metadataOnlyResults.isNotEmpty()) {
                        item(
                            key = "header:metadata",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "month",
                        ) {
                            // The count grows while the background pass runs, so
                            // it fades between values instead of flickering.
                            AnimatedContent(
                                targetState = metadataOnlyResults.size,
                                transitionSpec = {
                                    fadeIn(tween(160)) togetherWith fadeOut(tween(100))
                                },
                                label = "metadata-count",
                            ) { count ->
                                Text(
                                    text = stringResource(R.string.search_metadata_header, count),
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
                                )
                            }
                        }
                        metadataOnlyResults
                            .groupBy { monthLabel(it.dateAdded, unknownDateLabel) }
                            .forEach { (label, group) ->
                                item(
                                    key = "header:meta:$label",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "month",
                                ) {
                                    Text(
                                        text = label,
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .animateItem()
                                            .padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                                    )
                                }
                                items(group, key = { "search:${it.uri}" }) { item ->
                                    SearchHit(
                                        item = item,
                                        metadataOnly = true,
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(180),
                                            fadeOutSpec = tween(140),
                                            placementSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        ),
                                        results = results,
                                        keyboard = keyboard,
                                        onItemClick = onItemClick,
                                        onTilePositioned = onTilePositioned,
                                        onTileDisposed = onTileDisposed,
                                    )
                                }
                            }
                    }
                }
            }
        }
        }
    }
}

/** What the search body is showing; drives the crossfade between layouts. */
private enum class SearchState { Hint, Empty, Results }
