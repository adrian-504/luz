package app.iptvplayer.tv

import app.iptvplayer.tv.companion.PhoneCompanion
import java.net.Socket

/** One request to the companion over a plain socket, so the test can also send a Host it chooses. Status and body. */
internal fun companionCall(path: String, body: String, host: String = "127.0.0.1:${PhoneCompanion.PORT}"): Pair<Int, String> =
    Socket("127.0.0.1", PhoneCompanion.PORT).use { socket ->
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n".toByteArray())
            write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        response.substringAfter(' ').substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
    }
