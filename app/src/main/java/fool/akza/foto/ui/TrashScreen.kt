package fool.akza.foto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fool.akza.foto.R
import fool.akza.foto.data.TrashEntry
import fool.akza.foto.data.TrashStore
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foto's own trash: items deleted in-app while their bytes lived in
 * `filesDir/trash`. Isolated from Android's system trash, so it only ever lists
 * (and purges) files this app deleted.
 */
@Composable
fun TrashScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { TrashStore.list(context) }
    }

    val list = entries ?: emptyList()
    val totalBytes = remember(list) { list.sumOf { it.sizeBytes } }

    FrostedHeaderScaffold(
        background = AmoledBlack,
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsHeader(title = stringResource(R.string.storage_trash_title), onBack = onBack)
                IconButton(
                    onClick = { showEmptyConfirm = true },
                    enabled = list.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.storage_empty_trash_title),
                        tint = if (list.isEmpty()) TextMuted else TextPrimary,
                    )
                }
            }
        },
    ) { contentModifier ->
        if (entries != null && list.isEmpty()) {
            Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.storage_trash_empty),
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = contentModifier.navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = frostedContentTopPadding(8.dp),
                    end = 16.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (list.isNotEmpty()) {
                    item {
                        Text(
                            text = pluralStringResource(
                                R.plurals.trash_summary,
                                list.size,
                                list.size,
                                formatFileSize(totalBytes),
                            ),
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                items(list, key = { it.id }) { entry ->
                    TrashRow(
                        entry = entry,
                        onRestore = {
                            scope.launch {
                                withContext(Dispatchers.IO) { TrashStore.restore(context, entry) }
                                entries = withContext(Dispatchers.IO) { TrashStore.list(context) }
                                onChanged()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { TrashStore.remove(context, entry) }
                                entries = withContext(Dispatchers.IO) { TrashStore.list(context) }
                                onChanged()
                            }
                        },
                    )
                }
            }
        }
    }

    if (showEmptyConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.empty_trash_confirm_title),
            message = stringResource(R.string.empty_trash_confirm_message),
            confirmLabel = stringResource(R.string.action_empty),
            destructive = true,
            onConfirm = {
                showEmptyConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { TrashStore.clear(context) }
                    entries = emptyList()
                    onChanged()
                }
            },
            onDismiss = { showEmptyConfirm = false },
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, onRestore: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ThemeSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(ThemeSurface),
            ) {
                AsyncImage(
                    model = entry.payload,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (entry.isVideo) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(entry.sizeBytes),
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(
                    imageVector = Icons.Outlined.Restore,
                    contentDescription = stringResource(R.string.trash_restore),
                    tint = TextPrimary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = fool.akza.foto.ui.theme.DeleteRed,
                )
            }
        }
    }
}
