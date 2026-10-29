package app.iptvplayer.tv

import android.net.Uri
import app.iptvplayer.tv.companion.PhoneCompanion
import java.net.Socket

/** One request to the companion over a plain socket, so the test can also send a Host it chooses. Status and body. */
internal fun companionCall(path: String, body: String?, host: String = "127.0.0.1:${PhoneCompanion.PORT}"): Pair<Int, String> =
    Socket("127.0.0.1", PhoneCompanion.PORT).use { socket ->
        val bytes = body.orEmpty().toByteArray()
        socket.getOutputStream().apply {
            // No body: a GET, as the phone's browsing makes.
            if (body == null) {
                write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
                flush()
                return@use socket.getInputStream().readBytes().toString(Charsets.UTF_8).let {
                    it.substringAfter(' ').substringBefore(' ').toInt() to it.substringAfter("\r\n\r\n")
                }
            }
            write("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n".toByteArray())
            write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        response.substringAfter(' ').substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
    }

/** A GET as the phone's browsing makes it, with the phone's [token] added to [path]'s query. */
internal fun companionGet(path: String, token: String): Pair<Int, String> =
    companionCall(Uri.parse(path).buildUpon().appendQueryParameter("token", token).build().toString(), null)

/**
 * What the TV tells a paired phone over Server-Sent Events during [millis]: the raw stream, for tests to look through
 * (the TV's name, what is playing, a provider form's progress).
 */
internal fun companionEvents(token: String, millis: Int, host: String = "127.0.0.1:${PhoneCompanion.PORT}"): String =
    Socket("127.0.0.1", PhoneCompanion.PORT).use { socket ->
        val path = Uri.parse("/api/events").buildUpon().appendQueryParameter("token", token).build().toString()
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
            flush()
        }
        socket.soTimeout = 200
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        val until = System.currentTimeMillis() + millis
        val input = socket.getInputStream()
        while (System.currentTimeMillis() < until) {
            val count = try {
                input.read(buffer)
            } catch (_: java.net.SocketTimeoutException) {
                0
            }
            if (count < 0) break
            text.append(String(buffer, 0, count, Charsets.UTF_8))
        }
        text.toString()
    }
