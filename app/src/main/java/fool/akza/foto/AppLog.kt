package fool.akza.foto

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Central log tag. Failures that used to be swallowed silently are logged here
 * so field issues are diagnosable; debug logs are dropped unless the tag is
 * loggable (release builds stay quiet).
 */
object AppLog {
    private const val TAG = "Foto"

    fun d(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }
}

/**
 * Last-chance crash capture.
 *
 * A Java crash writes its stack trace to app storage before the default handler
 * takes the process down, so a failure that only happens on one device can be
 * shared from Settings → About instead of guessed at from a description. Native
 * crashes never reach this handler.
 */
object CrashLog {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 200_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("Foto crash")
            appendLine("when: ${Instant.now()}")
            appendLine("thread: ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
        }
        runCatching { File(context.filesDir, FILE_NAME).writeText(text.take(MAX_CHARS)) }
    }
}
