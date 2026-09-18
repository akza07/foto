package fool.akza.foto.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fool.akza.foto.R
import java.util.Locale

/**
 * Human-readable byte size (B / KB / MB / GB). The numeric part is formatted
 * with a stable US locale; the unit comes from a localized resource so the
 * string stays translatable.
 */
@Composable
internal fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> stringResource(R.string.size_gb, String.format(Locale.US, "%.2f", gb))
        mb >= 1 -> stringResource(R.string.size_mb, String.format(Locale.US, "%.1f", mb))
        kb >= 1 -> stringResource(R.string.size_kb, String.format(Locale.US, "%.0f", kb))
        else -> stringResource(R.string.size_bytes, bytes)
    }
}
