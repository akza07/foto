package com.phouton.phooto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary

private val SearchGridSpacing = 8.dp

/** Month/year bucket label for a `dateAdded` (epoch seconds). */
private fun monthLabel(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Unknown date"
    val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochSeconds * 1000L))
}

/**
 * Flat, name-filtered search over the whole library. [items] is the full set of
 * loaded media (MediaStore + SAF-browsed), so results include content inside
 * `.nomedia` folders that MediaStore alone would miss.
 */
@Composable
fun SearchScreen(
    items: List<MediaItem>,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    minTileSize: Dp = 112.dp,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val results = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            items.filter { it.displayName?.contains(q, ignoreCase = true) == true }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        // Header: back arrow + inline query field.
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
                        text = "Search photos and videos",
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
                        contentDescription = "Clear",
                        tint = TextMuted,
                    )
                }
            }
        }

        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        when {
            query.isBlank() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search by file name", color = TextMuted, fontSize = 14.sp)
                }
            }
            results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results", color = TextMuted, fontSize = 14.sp)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minTileSize),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp + bottomInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SearchGridSpacing),
                    verticalArrangement = Arrangement.spacedBy(SearchGridSpacing),
                ) {
                    // Group hits by month so long result sets stay navigable.
                    results
                        .groupBy { monthLabel(it.dateAdded) }
                        .forEach { (label, group) ->
                            item(
                                key = "header:$label",
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
                                ImageTile(
                                    item = item,
                                    onClick = { rect ->
                                        keyboard?.hide()
                                        onItemClick(results, results.indexOf(item), rect)
                                    },
                                )
                            }
                        }
                }
            }
        }
    }
}
