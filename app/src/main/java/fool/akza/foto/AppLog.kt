package fool.akza.foto

import android.util.Log

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
