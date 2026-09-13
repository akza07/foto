package com.phouton.phooto.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message that is resolved in composition rather than at the point
 * it is produced. Event callbacks (delete, copy, move) can't call the composable
 * `stringResource`, and resolving them from `LocalContext` bypasses configuration
 * changes; storing the resource reference instead keeps both correct.
 */
internal sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Qty(@PluralsRes val id: Int, val count: Int) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> if (args.isEmpty()) {
            stringResource(id)
        } else {
            stringResource(id, *args.toTypedArray())
        }
        is Qty -> pluralStringResource(id, count, count)
    }
}
