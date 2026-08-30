package com.phouton.phooto.data.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

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
 */
object VideoFrameCache {
    private const val DIR_NAME = "frames"
    private const val POSTER_NAME = "poster.jpg"
    private const val JPEG_QUALITY = 85

    private fun baseDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun dirFor(context: Context, uri: Uri): File =
        File(baseDir(context), sha1Hex(uri.toString()))

    private fun posterFile(dir: File): File = File(dir, POSTER_NAME)

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) hex.append('0')
            hex.append(v.toString(16))
        }
        return hex.toString()
    }

    /** Returns true if a readable poster exists on disk. */
    fun has(context: Context, uri: Uri): Boolean {
        val f = posterFile(dirFor(context, uri))
        return f.isFile && f.length() > 0L
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

    /** Persists the poster atomically. Returns true on success. */
    fun save(context: Context, uri: Uri, poster: Bitmap): Boolean {
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
}
