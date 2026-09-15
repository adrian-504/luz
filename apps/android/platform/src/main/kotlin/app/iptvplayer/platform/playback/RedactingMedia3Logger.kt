package app.iptvplayer.platform.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.iptvplayer.domain.security.Redactor

/**
 * Routes Media3's internal logging through URL redaction (docs/SECURITY.md §4): stream URLs can carry credentials in
 * their path or query, and Media3 includes URIs in some messages. Throwables are reduced to class name and redacted
 * message; their stack traces are not logged because nested messages cannot be redacted reliably.
 */
@OptIn(UnstableApi::class)
object RedactingMedia3Logger : androidx.media3.common.util.Log.Logger {
    private val url = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"'<>]+")

    fun install() {
        androidx.media3.common.util.Log.setLogger(this)
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_WARNING)
    }

    fun redact(text: String?): String = text?.let { url.replace(it) { match -> Redactor.origin(match.value) } }.orEmpty()

    private fun format(message: String, throwable: Throwable?): String {
        if (throwable == null) return redact(message)
        return redact(message) + " [" + throwable.javaClass.name + ": " + redact(throwable.message) + "]"
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        Log.d(tag, format(message, throwable))
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        Log.i(tag, format(message, throwable))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, format(message, throwable))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, format(message, throwable))
    }
}
