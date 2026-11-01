package fool.akza.foto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaFolder
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.GalleryDirectory
import fool.akza.foto.data.SelectionActionPlacement
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as SurfaceColor
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/** Holds the gallery's selection-mode state and the set of selected items/folders. */
data class SelectionState(
    val active: Boolean = false,
    val selected: List<MediaItem> = emptyList(),
    val selectedFolders: List<MediaFolder> = emptyList(),
    val selectedDirectories: List<GalleryDirectory> = emptyList(),
)

/** Number of selection units (folders count as 1 each, loose items as 1 each). */
val SelectionState.size: Int get() = selected.size + selectedFolders.size + selectedDirectories.size

/**
 * Number of media items the selection resolves to: a picked album or parent
 * directory contributes everything it contains, so the count in the app bar
 * matches what a delete/copy/share will actually act on.
 */
val SelectionState.itemCount: Int
    get() = selected.size +
        selectedFolders.sumOf { it.count } +
        selectedDirectories.sumOf { it.folder.count }

/**
 * Height of the full-width bottom bar; grids reserve this plus a gap so the
 * last row can scroll clear of it.
 */
internal val SelectionBottomBarHeight = 68.dp

/**
 * Height of the side rail (four stacked actions + padding), measured including
 * the label line box. Grids reserve it at the end of the list.
 */
internal val SelectionRailHeight = 288.dp

/** Width of the side rail. Kept narrow so it covers as little grid as possible. */
internal val SelectionRailWidth = 76.dp

/** Where the selection bar is anchored for a given placement. */
internal fun SelectionActionPlacement.anchor(): Alignment = when (this) {
    SelectionActionPlacement.BOTTOM -> Alignment.BottomCenter
    SelectionActionPlacement.LEFT -> Alignment.BottomStart
    SelectionActionPlacement.RIGHT -> Alignment.BottomEnd
}

/**
 * Vertical room a grid must reserve at the end of its list while selecting: the
 * bottom bar's height, or the side rail's height so the last row can scroll
 * above the rail instead of hiding behind it.
 */
internal fun SelectionActionPlacement.reservedEndSpace(): Dp = when (this) {
    SelectionActionPlacement.BOTTOM -> SelectionBottomBarHeight
    SelectionActionPlacement.LEFT, SelectionActionPlacement.RIGHT -> SelectionRailHeight
}

/**
 * Floating multi-select action bar. [SelectionActionPlacement.BOTTOM] keeps the
 * original full-width bar; [SelectionActionPlacement.LEFT] / [RIGHT] render a
 * compact vertical rail hugging that edge so the actions stay inside the reach
 * of a thumb in one-handed use.
 */
@Composable
internal fun SelectionActionBar(
    placement: SelectionActionPlacement,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (placement == SelectionActionPlacement.BOTTOM) {
        SelectionBottomBar(onDelete, onCopy, onMove, onShare, modifier)
    } else {
        SelectionSideRail(onDelete, onCopy, onMove, onShare, modifier)
    }
}

/** Full-width bar stacked along the bottom edge (the original layout). */
@Composable
private fun SelectionBottomBar(
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

/**
 * Compact vertical rail that hugs the left or right edge, sitting just above
 * the navigation bar so every action lands under the thumb.
 */
@Composable
private fun SelectionSideRail(
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .width(SelectionRailWidth),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceColor,
        shadowElevation = 12.dp,
    ) {
        Box {
            // The rail overlaps the grid, so swallow taps that land between the
            // actions: without this they would toggle a tile hidden behind it.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { } },
            )
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SelectionRailAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), onDelete)
                SelectionRailAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy)
                SelectionRailAction(
                    Icons.AutoMirrored.Outlined.DriveFileMove,
                    stringResource(R.string.action_move),
                    onMove,
                )
                SelectionRailAction(Icons.Outlined.Share, stringResource(R.string.action_share), onShare)
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

/** One action in the vertical rail: icon over label, sized to the rail width. */
@Composable
private fun SelectionRailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
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
