package com.phouton.phooto.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
import com.phouton.phooto.ui.theme.Surface as ThemeSurface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip

private val RowShape = RoundedCornerShape(12.dp)

@Composable
fun SettingsScreen(onBack: () -> Unit, onSettingsChanged: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsRepository.settings.collectAsState()

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // Non-persistable provider; access still valid for this session.
            }
            SettingsRepository.addTreeUri(uri.toString())
            onSettingsChanged()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        // Header.
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
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Settings",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Content Discovery",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            item {
                SettingsToggleRow(
                    title = "Respect .nomedia",
                    subtitle = "Hide media in folders marked with a .nomedia file.",
                    checked = settings.respectNoMedia,
                    onCheckedChange = {
                        SettingsRepository.setRespectNoMedia(it)
                        onSettingsChanged()
                    },
                )
            }

            item {
                SettingsToggleRow(
                    title = "Require explicit permission",
                    subtitle = if (settings.requireExplicit) {
                        "Show only media inside the directories you add."
                    } else {
                        "Show all media, except the directories you add."
                    },
                    checked = settings.requireExplicit,
                    onCheckedChange = {
                        SettingsRepository.setRequireExplicit(it)
                        onSettingsChanged()
                    },
                )
            }

            // The directory list + folder picker are always available; only the
            // semantics change based on the explicit-permission flag.
            item {
                Text(
                    text = when {
                        settings.selectedTreeUris.isEmpty() -> {
                            if (settings.requireExplicit) {
                                "No directories added. Nothing will be shown."
                            } else {
                                "No directories added. All media is shown."
                            }
                        }
                        settings.requireExplicit -> {
                            "${settings.selectedTreeUris.size} director${
                                if (settings.selectedTreeUris.size == 1) "y" else "ies"
                            } selected — only these are shown."
                        }
                        else -> {
                            "${settings.selectedTreeUris.size} director${
                                if (settings.selectedTreeUris.size == 1) "y" else "ies"
                            } selected — these are hidden."
                        }
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(settings.selectedTreeUris) { uri ->
                DirectoryRow(
                    treeUri = uri,
                    onRemove = {
                        SettingsRepository.removeTreeUri(uri)
                        onSettingsChanged()
                    },
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .clickable { treePicker.launch(null) },
                    shape = RowShape,
                    color = ThemeSurface,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.width(20.dp),
                            )
                        }
                        Text(
                            text = "Add directory",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape),
        shape = RowShape,
        color = ThemeSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AmoledBlack,
                    checkedTrackColor = TextPrimary,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Border,
                    uncheckedBorderColor = Border,
                ),
            )
        }
    }
}

@Composable
private fun DirectoryRow(treeUri: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape),
        shape = RowShape,
        color = ThemeSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = directoryName(treeUri),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = treeUri,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove",
                    tint = TextMuted,
                    modifier = Modifier.width(20.dp),
                )
            }
        }
    }
}

private fun directoryName(treeUri: String): String {
    return try {
        val docId = Uri.parse(treeUri).lastPathSegment?.substringAfter(":")
        val raw = docId?.substringAfterLast("/", "")?.replace("_", " ")
        raw?.takeIf { it.isNotBlank() } ?: "Selected directory"
    } catch (_: Exception) {
        "Selected directory"
    }
}
