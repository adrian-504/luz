package app.iptvplayer.tv.companion

import android.view.KeyEvent
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * The phone companion's server (ADR-0050): the page, pairing, the field under the remote as Server-Sent Events, and
 * typing and key presses from the phone. HTTP/1.1, one request per connection, with hard limits on size, time and
 * connections. It answers only requests addressed to [hosts] (the TV's home-network address, or the TV itself), which
 * keeps a web page elsewhere from reaching it through a renamed address.
 */
class CompanionServer(
    port: Int,
    private val hosts: () -> Set<String>,
    private val page: () -> ByteArray,
    private val pairing: CompanionPairing,
    /** Runs a change to the screen on the main thread. */
    private val onMain: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port), BACKLOG)
    }
    private val connections = Semaphore(MAX_CONNECTIONS)

    val port: Int get() = socket.localPort

    init {
        thread(name = "companion-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                if (!connections.tryAcquire()) {
                    client.close()
                    continue
                }
                thread(name = "companion-client", isDaemon = true) {
                    try {
                        client.use { handle(it) }
                    } catch (_: IOException) {
                        // The phone went away mid-request; nothing to tell anyone.
                    } finally {
                        connections.release()
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val head = readHead(input) ?: return respond(out, 431, TEXT, "Request too large".toByteArray())
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return respond(out, 400, TEXT, "Bad request".toByteArray())
        val (method, target) = parts
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
        }.toMap()
        if (headers["host"]?.lowercase() !in hosts()) return respond(out, 421, TEXT, "Wrong address".toByteArray())
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
        }.toMap()
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> respond(out, 200, HTML, page())
            method == "GET" && path == "/api/events" ->
                if (pairing.allows(query["token"])) streamEvents(out, query.getValue("token")) else respond(out, 401, JSON, ERROR_UNPAIRED)
            method == "POST" && path.startsWith("/api/") -> {
                val length = headers["content-length"]?.toIntOrNull() ?: return respond(out, 411, TEXT, "Length required".toByteArray())
                if (length !in 0..MAX_BODY) return respond(out, 413, TEXT, "Too large".toByteArray())
                val body = runCatching { JSONObject(String(input.readNBytesCompat(length), Charsets.UTF_8)) }.getOrNull()
                    ?: return respond(out, 400, JSON, """{"error":"bad-json"}""".toByteArray())
                post(path, body, out)
            }
            else -> respond(out, 404, TEXT, "Not found".toByteArray())
        }
    }

    private fun post(path: String, body: JSONObject, out: OutputStream) {
        if (path == "/api/pair") {
            val token = pairing.pair(body.optString("code"), body.optString("name"))
            return if (token == null) {
                respond(out, 403, JSON, """{"error":"wrong-code"}""".toByteArray())
            } else {
                respond(out, 200, JSON, JSONObject().put("token", token).toString().toByteArray())
            }
        }
        if (!pairing.allows(body.optString("token").ifEmpty { null })) return respond(out, 401, JSON, ERROR_UNPAIRED)
        when (path) {
            "/api/text" -> {
                val field = CompanionHub.field.value
                if (field == null ||
                    field.id != body.optLong("field")
                ) {
                    return respond(out, 409, JSON, """{"error":"field-gone"}""".toByteArray())
                }
                val text = body.optString("text").take(MAX_TEXT)
                onMain { field.onText(text) }
            }
            "/api/done" -> {
                val field = CompanionHub.field.value
                if (field != null && field.id == body.optLong("field")) onMain { field.onDone() }
            }
            "/api/key" -> {
                val code = KEYS[body.optString("key")] ?: return respond(out, 400, JSON, """{"error":"unknown-key"}""".toByteArray())
                CompanionHub.press(code)
            }
            else -> return respond(out, 404, JSON, """{"error":"not-found"}""".toByteArray())
        }
        respond(out, 200, JSON, """{"ok":true}""".toByteArray())
    }

    /** Tells the phone about the field under the remote whenever it changes, and that the TV is still there. */
    private fun streamEvents(out: OutputStream, token: String) {
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n" +
                    "Connection: keep-alive\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
        var sent: String? = null
        var lastWriteMs = 0L
        // Ends when the server stops or the viewer forgets this phone in Settings.
        while (!socket.isClosed && pairing.allows(token)) {
            val field = CompanionHub.field.value
            val event = JSONObject().apply {
                if (field != null) {
                    put("id", field.id)
                    put("label", field.label)
                    put("password", field.password)
                    if (field.password) put("length", field.value.length) else put("value", field.value)
                }
            }.toString()
            val now = System.currentTimeMillis()
            if (event != sent) {
                out.write("event: field\ndata: $event\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
                sent = event
                lastWriteMs = now
            } else if (now - lastWriteMs > HEARTBEAT_MS) {
                // A comment line: keeps the connection open through home routers and shows a dead phone as a failed write.
                out.write(": still here\n\n".toByteArray())
                out.flush()
                lastWriteMs = now
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** The request line and headers, up to the blank line; null if they run past [MAX_HEAD]. */
    private fun readHead(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            matched = if (b.toChar() == END[matched]) {
                matched + 1
            } else if (b.toChar() == END[0]) {
                1
            } else {
                0
            }
            if (matched == END.length) return bytes.toString(Charsets.ISO_8859_1.name()).dropLast(END.length)
        }
        return null
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val count = read(buffer, read, n - read)
            if (count < 0) throw IOException("body ended early")
            read += count
        }
        return buffer
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        out.write(
            (
                "HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 8
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 16 * 1024
        const val MAX_TEXT = 500
        const val HEARTBEAT_MS = 15_000L
        const val POLL_MS = 100L
        const val END = "\r\n\r\n"
        const val TEXT = "text/plain; charset=utf-8"
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        val ERROR_UNPAIRED = """{"error":"not-paired"}""".toByteArray()
        val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            409 to "Conflict", 411 to "Length Required", 413 to "Payload Too Large", 421 to "Misdirected Request",
            431 to "Request Header Fields Too Large",
        )
        val KEYS = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
            "back" to KeyEvent.KEYCODE_BACK,
            "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
