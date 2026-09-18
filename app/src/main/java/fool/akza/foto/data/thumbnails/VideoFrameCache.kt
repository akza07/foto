package fool.akza.foto.data.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Persistent, app-private on-disk cache of the video poster (first frame).
 *
 * Stored under `filesDir/frames/<key>/poster.jpg`:
 *  - `filesDir` is app-internal → other apps can't read it (sandbox).
 *  - Excluded from Android auto-backup via data_extraction_rules.xml /
 *    backup_rules.xml, so posters never leave the device.
 *  - Writes are atomic: encode to `poster.jpg.tmp`, then rename into place.
 *
 * A poster is considered present only when the file exists and decodes;
 * otherwise [load] returns null and the caller falls back to MediaStore's
 * system thumbnail / Coil.
 *
 * Freshness is marker-based, not size-based: a successful frame-0 save also
 * writes `poster.meta` recording the pipeline version. This matters for videos
 * whose native resolution is smaller than the target —
 * `getScaledFrameAtTime` never upscales, so a 640×480 video legitimately
 * stores a 640px poster and would fail a `>= 720px` size check forever,
 * re-extracting (and re-showing the caching pill) on every launch. A failed
 * extraction writes `failed.meta` instead, so unreadable videos can be skipped
 * (and not counted) for a while — see [isFailureFresh].
 */
object VideoFrameCache {
    private const val DIR_NAME = "frames"
    private const val POSTER_NAME = "poster.jpg"
    private const val JPEG_QUALITY = 85

    /**
     * Version of the frame-0 poster pipeline. Bump when extraction semantics
     * change (frame position, scaling intent) so existing posters regenerate.
     */
    private const val POSTER_FORMAT_VERSION = 1
    private const val FRESHNESS_MARKER = "poster.meta"
    private const val FAILURE_MARKER = "failed.meta"

    /** How long a failed extraction suppresses retries in the warm pass. */
    const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1000

    private fun baseDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun dirFor(context: Context, uri: Uri): File =
        File(baseDir(context), sha1Hex(uri.toString()))

    private fun posterFile(dir: File): File = File(dir, POSTER_NAME)

    /**
     * True when a poster written by the current pipeline version already
     * exists on disk for [stamp]. This is the warm-pass freshness gate: it
     * passes for videos whose native resolution is below the requested long
     * edge (the extractor stores what it got), and fails for legacy posters
     * predating the marker or whose source file changed since the poster was
     * written (so an edited/replaced video regenerates).
     */
    fun hasFreshPoster(context: Context, uri: Uri, stamp: String): Boolean {
        val dir = dirFor(context, uri)
        val poster = posterFile(dir)
        val meta = File(dir, FRESHNESS_MARKER)
        if (!poster.isFile || poster.length() <= 0L || !meta.isFile) return false
        val values = readMeta(meta)
        return values["v"]?.toIntOrNull() == POSTER_FORMAT_VERSION &&
            values["stamp"] == stamp
    }

    /**
     * True when [markFailed] recorded a failure recently enough that the warm
     * pass should not retry (24 h). Without this, an unreadable video counts
     * as pending work — and flashes the caching pill — on every launch. A
     * failure from a different [stamp] (the file changed) does not suppress.
     */
    fun isFailureFresh(context: Context, uri: Uri, stamp: String): Boolean {
        val f = File(dirFor(context, uri), FAILURE_MARKER)
        if (!f.isFile) return false
        val writtenAt = f.lastModified()
        return writtenAt > 0L &&
            System.currentTimeMillis() - writtenAt < FAILURE_RETRY_MS &&
            readMeta(f)["stamp"] == stamp
    }

    /** Records that extraction failed for [uri], suppressing retries for 24 h. */
    fun markFailed(context: Context, uri: Uri, stamp: String) {
        try {
            val dir = dirFor(context, uri)
            dir.mkdirs()
            File(dir, FAILURE_MARKER).apply {
                writeText("stamp=$stamp\nfailed\n")
                // lastModified is the freshness signal; pin it explicitly in
                // case the filesystem rounds or the write is a no-op rewrite.
                setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
        }
    }

    /** Loads the cached poster, or null if missing/corrupt. */
    fun load(context: Context, uri: Uri): Bitmap? {
        val f = posterFile(dirFor(context, uri))
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Persists the poster atomically, followed by the freshness marker. The
     * marker is written last so a crash mid-save leaves a versionless dir,
     * which [hasFreshPoster] treats as absent (the safe retry direction).
     * Returns true on success.
     */
    fun save(context: Context, uri: Uri, poster: Bitmap, stamp: String): Boolean {
        val dir = dirFor(context, uri)
        val target = posterFile(dir)
        val tmp = File(dir, "$POSTER_NAME.tmp")
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            tmp.outputStream().use { out ->
                if (!poster.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
            }
            if (!tmp.renameTo(target)) return false
            // Written after the poster lands: marker present + poster present
            // is the "fresh" invariant.
            File(dir, FRESHNESS_MARKER).writeText(
                "v=$POSTER_FORMAT_VERSION\nstamp=$stamp\n",
            )
            true
        } catch (_: Exception) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
            false
        }
    }

    /** Deletes the entire poster cache (e.g. from Settings). */
    fun clear(context: Context) {
        try {
            baseDir(context).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /**
     * Deletes the cached poster and both markers for [uri] (e.g. the file was
     * deleted), so a later file at the same URI never renders the old poster.
     */
    fun evict(context: Context, uri: Uri) {
        try {
            dirFor(context, uri).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Parses `key=value` lines from a marker file; empty on absence/corruption. */
    private fun readMeta(meta: File): Map<String, String> = try {
        meta.readText().lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
            }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
