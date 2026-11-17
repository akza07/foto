package fool.akza.foto.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.data.similar.SimilarGroup
import fool.akza.foto.data.similar.folderNameError
import fool.akza.foto.data.similar.nextGroupedName
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.RaisedSurface
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

@Composable
internal fun GroupFolderSheet(
    group: SimilarGroup,
    existingNames: Map<String, Set<String>>?,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, destination: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDestination by remember(group.id) { mutableStateOf(0) } // 0 = same, 1 = parent
    val samePath = group.sameFolder
    val parentPath = group.parentFolder
    val destinationPath = if (selectedDestination == 0) {
        samePath ?: ""
    } else {
        parentPath ?: ""
    }
    // Names are scoped to the selected directory: Grouped(1) may be free in
    // the source folder but taken in the parent (or vice versa).
    val names = existingNames?.get(destinationPath)
    val namesReady = existingNames != null && names != null
    var nameEdited by remember(group.id) { mutableStateOf(false) }
    var name by remember(group.id, destinationPath) {
        mutableStateOf(nextGroupedName(emptySet()))
    }
    var lastDestination by remember(group.id) { mutableStateOf(destinationPath) }
    LaunchedEffect(destinationPath) {
        if (lastDestination != destinationPath) {
            lastDestination = destinationPath
            nameEdited = false
        }
    }
    // Fill the default once the folder query lands, unless the user already
    // typed a name. A destination switch resets the suggestion.
    LaunchedEffect(names) {
        if (namesReady && !nameEdited) {
            name = nextGroupedName(names.orEmpty())
        }
    }
    val error = if (namesReady) folderNameError(name, names.orEmpty()) else null

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Scrim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // Bottom sheet.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = Color(0xFF09090B),
            border = BorderStroke(1.dp, Border),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Grab handle.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(Border.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                    )
                }

                // Header.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Group ${group.items.size} photos",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = null,
                            tint = TextMuted,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Preview strip.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val previewItems = group.items.take(4)
                    previewItems.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        ) {
                            Thumb(
                                item = item,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    val remainder = group.items.size - previewItems.size
                    if (remainder > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ThemeSurface,
                            border = BorderStroke(1.dp, Border),
                            modifier = Modifier.size(52.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+$remainder",
                                    color = TextMuted,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Folder name.
                Text(
                    text = "Folder name",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ThemeSurface,
                    border = BorderStroke(1.dp, Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                nameEdited = true
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 16.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        color = DeleteRed,
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(4.dp))
                val helperFolder = destinationPath.ifBlank { "this folder" }
                Text(
                    text = if (namesReady) {
                        "Next available name in $helperFolder"
                    } else {
                        "Checking existing folders…"
                    },
                    color = TextMuted,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.height(16.dp))

                // Destination.
                Text(
                    text = "Destination",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))

                // Same folder row.
                DestinationRow(
                    title = "Same folder",
                    subtitle = group.sameFolder ?: "This folder",
                    selected = selectedDestination == 0,
                    enabled = true,
                    onClick = { selectedDestination = 0 },
                )
                Spacer(Modifier.height(8.dp))
                // Parent folder row. SAF groups can only move within their
                // granted tree until the tree walk is implemented.
                val parentEnabled = group.parentFolder != null &&
                    group.items.none { it.safTreeUri != null }
                DestinationRow(
                    title = "Parent folder",
                    subtitle = group.parentFolder ?: "Not available",
                    selected = selectedDestination == 1,
                    enabled = parentEnabled,
                    onClick = { if (parentEnabled) selectedDestination = 1 },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Default is the folder these photos are already in.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The photos will be moved into $name inside the chosen destination.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.height(16.dp))

                // Primary button.
                Surface(
                    onClick = {
                        if (!isCreating && namesReady && error == null) {
                            onCreate(name.trim(), destinationPath)
                        }
                    },
                    enabled = !isCreating && namesReady && error == null,
                    shape = RoundedCornerShape(12.dp),
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = AmoledBlack,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = "Create folder & move",
                                color = AmoledBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Ghost cancel.
                TextButton(
                    onClick = onDismiss,
                    enabled = !isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(
                        text = "Cancel",
                        color = TextMuted,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) TextPrimary else TextMuted,
        animationSpec = tween(150),
        label = "destTint",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) RaisedSurface else ThemeSurface.copy(alpha = 0.0f),
        border = BorderStroke(1.dp, if (selected) Border else Border.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) tint else TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(1.dp, Border, CircleShape),
                )
            }
        }
    }
}
