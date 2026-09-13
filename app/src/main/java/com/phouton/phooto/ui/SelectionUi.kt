package com.phouton.phooto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.R
import com.phouton.phooto.data.MediaFolder
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
import com.phouton.phooto.ui.theme.Surface as SurfaceColor
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary

/** Holds the gallery's selection-mode state and the set of selected items/folders. */
data class SelectionState(
    val active: Boolean = false,
    val selected: List<MediaItem> = emptyList(),
    val selectedFolders: List<MediaFolder> = emptyList(),
)

/** Number of selection units (folders count as 1 each, loose items as 1 each). */
val SelectionState.size: Int get() = selected.size + selectedFolders.size

/** Floating bottom action bar shown in selection mode with the bulk actions. */
@Composable
internal fun SelectionActionBar(
    count: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = SurfaceColor,
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.selection_exit),
                        tint = TextPrimary,
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.selection_count, count, count),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), onDelete)
                SelectionAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy)
                SelectionAction(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.action_move), onMove)
                SelectionAction(Icons.Outlined.Share, stringResource(R.string.action_share), onShare)
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Circular selection indicator shown in the top-left of a tile in selection mode. */
@Composable
internal fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) TextPrimary else AmoledBlack.copy(alpha = 0.5f))
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = TextPrimary,
                shape = RoundedCornerShape(50),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.selection_selected),
                tint = SurfaceColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
