package com.baybin.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last couple hundred events, shown on the main screen and mirrored to logcat
 * (tag BayBinPhone) so tools/probe.py can read them too.
 */
object EventLog {
    private const val TAG = "BayBinPhone"
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Called on the main thread after every new line. */
    var listener: (() -> Unit)? = null

    fun add(message: String) {
        val safe = redact(message)
        Log.i(TAG, safe)
        // SimpleDateFormat is not thread-safe; the reader thread, the pipeline worker and
        // the main thread all log. Formatting outside the lock has thrown and killed the process.
        synchronized(lines) {
            lines.addLast("${clock.format(Date())}  $safe")
            while (lines.size > MAX) lines.removeFirst()
        }
        main.post { listener?.invoke() }
    }

    /** Never let a token or Authorization header reach logcat or the on-screen log. */
    private fun redact(message: String): String = message
        .replace(Regex("sk-[A-Za-z0-9._\\-]{8,}"), "[redacted]")
        .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [redacted]")

    fun text(): String = synchronized(lines) { lines.reversed().joinToString("\n") }
}
