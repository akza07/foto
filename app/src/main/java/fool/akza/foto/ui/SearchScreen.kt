package fool.akza.foto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import fool.akza.foto.data.MetadataStore
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

private val SearchGridSpacing = 8.dp

/** Quiet period before a metadata query runs, so per-keystroke scans can't queue. */
private const val SearchDebounceMs = 150L

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
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            metadataHits = emptySet()
            return@LaunchedEffect
        }
        // Typing is faster than a LIKE scan over the whole index; a short pause
        // keeps one query in flight instead of a queue of them.
        delay(SearchDebounceMs)
        metadataHits = MetadataStore.search(context, q)
    }

    val trimmedQuery = query.trim()
    val nameResults = remember(items, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            items.filter { it.displayName?.contains(trimmedQuery, ignoreCase = true) == true }
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
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        color = TextMuted,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
            if (query.isNotEmpty()) {
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
        when {
            query.isBlank() -> {
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
            results.isEmpty() -> {
                Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_no_results),
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
            else -> {
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
                    ) {
                        Text(
                            text = stringResource(R.string.search_scope),
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    // Name hits first, grouped by month so long result sets
                    // stay navigable.
                    nameResults
                        .groupBy { monthLabel(it.dateAdded, unknownDateLabel) }
                        .forEach { (label, group) ->
                            item(
                                key = "header:name:$label",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Text(
                                    text = label,
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        start = 4.dp,
                                        top = 8.dp,
                                        bottom = 2.dp,
                                    ),
                                )
                            }
                            items(group, key = { "search:${it.uri}" }) { item ->
                                SearchHit(
                                    item = item,
                                    metadataOnly = false,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
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
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.search_metadata_header,
                                    metadataOnlyResults.size,
                                ),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 16.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                        metadataOnlyResults
                            .groupBy { monthLabel(it.dateAdded, unknownDateLabel) }
                            .forEach { (label, group) ->
                                item(
                                    key = "header:meta:$label",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Text(
                                        text = label,
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(
                                            start = 4.dp,
                                            top = 8.dp,
                                            bottom = 2.dp,
                                        ),
                                    )
                                }
                                items(group, key = { "search:${it.uri}" }) { item ->
                                    SearchHit(
                                        item = item,
                                        metadataOnly = true,
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = null,
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
