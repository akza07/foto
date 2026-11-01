package fool.akza.foto.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/** Display name split into an editable base and a fixed extension. */
internal data class RenameNameParts(val base: String, val extension: String)

/**
 * Splits a file name at its last dot. Leading dots (hidden files) and trailing
 * dots are treated as part of the base, so the extension is only what follows a
 * real middle dot.
 */
internal fun splitRenameName(name: String): RenameNameParts {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.lastIndex) {
        RenameNameParts(name.substring(0, dot), name.substring(dot))
    } else {
        RenameNameParts(name, "")
    }
}

/** Why a typed base name can't be used, or null when it is valid. */
internal enum class RenameNameError { Empty, InvalidCharacters }

internal fun renameNameError(base: String): RenameNameError? {
    val trimmed = base.trim()
    return when {
        trimmed.isEmpty() -> RenameNameError.Empty
        trimmed == "." || trimmed == ".." ||
            trimmed.contains('/') || trimmed.contains('\\') -> RenameNameError.InvalidCharacters
        else -> null
    }
}

/**
 * Rename dialog. Only the base name is editable; the original extension stays
 * fixed, so a photo doesn't silently become a different type. Validation is
 * inline, the field is auto-focused with the old name selected, and Done on the
 * keyboard submits. MediaStore/SAF and the consent flow are handled by the
 * caller through [onRename].
 */
@Composable
internal fun RenameDialog(
    item: MediaItem,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val parts = remember(item.displayName) {
        splitRenameName(item.displayName.orEmpty().ifBlank { "file" })
    }
    var value by remember(item.uri, parts.base) {
        mutableStateOf(TextFieldValue(parts.base, selection = TextRange(0, parts.base.length)))
    }
    var error by remember { mutableStateOf<RenameNameError?>(null) }
    val focus = remember { FocusRequester() }

    fun submit() {
        val base = value.text.trim()
        val reason = renameNameError(base)
        error = reason
        if (reason == null) {
            val fullName = base + parts.extension
            if (fullName == item.displayName) onDismiss() else onRename(fullName)
        }
    }

    AnimatedDialog(onDismiss = onDismiss) {
        // Keep the card above the IME instead of letting the keyboard cover it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            // The dialog content is composed only once AnimatedDialog becomes
            // visible, so request focus here (not at the top of RenameDialog,
            // where the field does not exist yet) to open the keyboard.
            LaunchedEffect(Unit) {
                runCatching { focus.requestFocus() }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                shape = RoundedCornerShape(16.dp),
                color = ThemeSurface,
                border = BorderStroke(1.dp, Border),
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.rename_title),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.rename_hint),
                        color = TextMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(18.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmoledBlack)
                            .border(1.dp, Border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = {
                                value = it
                                error = null
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(TextPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focus),
                        )
                        if (parts.extension.isNotEmpty()) {
                            Text(
                                text = parts.extension,
                                color = TextMuted,
                                fontSize = 16.sp,
                            )
                        }
                    }

                    val currentError = error
                    if (currentError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                when (currentError) {
                                    RenameNameError.Empty -> R.string.rename_error_empty
                                    RenameNameError.InvalidCharacters -> R.string.rename_error_invalid
                                },
                            ),
                            color = DeleteRed,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RenameAction(
                            label = stringResource(R.string.action_cancel),
                            primary = false,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        RenameAction(
                            label = stringResource(R.string.action_rename),
                            primary = true,
                            onClick = { submit() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (primary) TextPrimary else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, Border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (primary) AmoledBlack else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
