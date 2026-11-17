package fool.akza.foto.data.similar

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.canonicalDirPath
import fool.akza.foto.data.mediaCollectionUri

/**
 * Gatekeeper for moving a similar group into a real `Grouped(n)` folder.
 *
 * Foto holds **no** manifest write permission on Android 11+ (API 30): there is
 * no `WRITE_EXTERNAL_STORAGE` and no `MANAGE_EXTERNAL_STORAGE` in the manifest.
 * Instead the app uses the two scoped-storage mechanisms Android provides:
 *
 * - **SAF trees** — when the items came from a granted document tree
 *   ([MediaItem.safTreeUri]), the tree grant already confers write access and
 *   [GroupedFolderMover] can move documents directly. No dialog is shown.
 * - **Per-batch write consent** — for MediaStore rows the app calls
 *   [MediaStore.createWriteRequest], which hands back an [IntentSender]. The
 *   system prompts the user once for the whole batch; the actual move only
 *   happens inside [complete], after that prompt returns `RESULT_OK`.
 *
 * A Compose caller should call [start] from a coroutine, then:
 * 1. [GroupMoveStart.NeedsConsent] — launch the `intentSender` with an
 *    `ActivityResultContracts.StartIntentSenderForResult`, and on `RESULT_OK`
 *    call [complete] with the returned [GroupMoveRequest].
 * 2. [GroupMoveStart.Done] — consume the [GroupMoveResult] directly (SAF path).
 * 3. [GroupMoveStart.Failed] — show the reason; nothing was moved.
 *
 * API 29 has no per-batch consent API (only per-item
 * `RecoverableSecurityException`), so MediaStore groups are rejected explicitly
 * rather than attempting an unauthorised write.
 */
internal object GroupMoveConsent {

    /** The authority of MediaStore content URIs (`content://media/...`). */
    private const val MEDIA_AUTHORITY = "media"

    /** Where an item came from — decides whether a consent dialog is needed. */
    private enum class Source { SAF, MEDIA_STORE }

    suspend fun start(
        context: Context,
        items: List<MediaItem>,
        destination: String?,
        folderName: String,
    ): GroupMoveStart {
        if (items.isEmpty()) return GroupMoveStart.Failed("No photos to move")
        if (folderName.trim().isEmpty()) return GroupMoveStart.Failed("Enter a folder name")

        val sources = items.map { classify(context, it) }
        if (sources.any { it == null }) {
            return GroupMoveStart.Failed("This item can't be moved")
        }
        val hasSaf = sources.any { it == Source.SAF }
        val hasMediaStore = sources.any { it == Source.MEDIA_STORE }
        if (hasSaf && hasMediaStore) {
            // One physical folder can't span a document tree and MediaStore, and
            // a single consent batch can't cover both providers.
            return GroupMoveStart.Failed("Can't mix SAF and MediaStore photos")
        }

        // A missing destination falls back to the shared/canonical folder of the
        // first item; GroupedFolderMover needs a concrete parent either way.
        val effectiveDestination = destination?.trim()?.ifBlank { null }
            ?: items.firstOrNull()?.canonicalDirPath()
        val request = GroupMoveRequest(items, effectiveDestination, folderName.trim())

        return if (hasSaf) {
            // The SAF tree grant already authorises the move — no system prompt.
            runCatching {
                GroupMoveStart.Done(
                    GroupedFolderMover.move(
                        context,
                        request.items,
                        request.destination,
                        request.folderName,
                    ),
                )
            }.getOrElse { error ->
                AppLog.w("similar: SAF grouping failed", error)
                GroupMoveStart.Failed("Move failed: ${error.message ?: "unknown error"}")
            }
        } else {
            startMediaStore(context, request)
        }
    }

    /** Call after the consent activity returns `RESULT_OK`. Assumes consent was granted. */
    suspend fun complete(
        context: Context,
        request: GroupMoveRequest,
    ): GroupMoveResult = runCatching {
        GroupedFolderMover.move(
            context,
            request.items,
            request.destination,
            request.folderName,
        )
    }.onFailure { error ->
        AppLog.w("similar: grouped move failed after consent", error)
    }.getOrDefault(GroupMoveResult(0, request.items.size, null))

    private fun startMediaStore(context: Context, request: GroupMoveRequest): GroupMoveStart {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 29 only offers per-item RecoverableSecurityException consent,
            // which cannot be batched. Fail loudly instead of writing blindly.
            return GroupMoveStart.Failed("This Android version needs per-file approval")
        }
        // MediaStore.createWriteRequest rejects the generic Files collection
        // URI the gallery stores; it needs the Images/Video item URI.
        val uris = request.items.map { item ->
            if (item.id != 0L) mediaCollectionUri(item.isVideo, item.id) else item.uri
        }.distinct()
        val sender = runCatching {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        }.onFailure { error ->
            AppLog.w("similar: write consent request failed", error)
        }.getOrNull()
        return if (sender != null) {
            GroupMoveStart.NeedsConsent(sender, request)
        } else {
            GroupMoveStart.Failed("Couldn't request write access")
        }
    }

    /**
     * Classifies an item by its backing provider. A non-null SAF tree or a
     * DocumentsContract document URI is SAF; only `content://media/...` is a
     * MediaStore row eligible for [MediaStore.createWriteRequest]. Anything else
     * is unsupported and must not be written.
     */
    private fun classify(context: Context, item: MediaItem): Source? {
        if (item.safTreeUri != null) return Source.SAF
        val uri: Uri = item.uri
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MEDIA_AUTHORITY) {
            return Source.MEDIA_STORE
        }
        if (DocumentsContract.isDocumentUri(context, uri)) return Source.SAF
        return null
    }
}

/** What a caller must do next after preparing a grouped move. */
internal sealed interface GroupMoveStart {
    /** Show the system write-consent dialog, then call `complete` on approval. */
    data class NeedsConsent(
        val intentSender: IntentSender,
        val request: GroupMoveRequest,
    ) : GroupMoveStart

    /** No consent needed (SAF); the move already ran. */
    data class Done(val result: GroupMoveResult) : GroupMoveStart

    /** Validation or platform failure; nothing was moved. */
    data class Failed(val reason: String) : GroupMoveStart
}

/** Snapshot of the requested move, carried through the consent round-trip. */
internal data class GroupMoveRequest(
    val items: List<MediaItem>,
    val destination: String?,
    val folderName: String,
)
