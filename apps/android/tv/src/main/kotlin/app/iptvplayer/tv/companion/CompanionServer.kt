package app.iptvplayer.tv.companion

import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone companion's server (ADR-0050): the page, pairing, the field under the remote as Server-Sent Events, and
 * typing, key presses and what to play from the phone, and the viewer's library to browse (CompanionLibrary). HTTP/1.1, one request per connection, with hard limits on size, time and
 * connections. It answers only requests addressed to [hosts] (the TV's home-network address, or the TV itself), which
 * keeps a web page elsewhere from reaching it through a renamed address.
 */
class CompanionServer(
    port: Int,
    private val hosts: () -> Set<String>,
    private val page: () -> ByteArray,
    private val pairing: CompanionPairing,
    private val library: CompanionLibrary,
    /** The TV's own name for the phone's header, or null when it has none worth showing. */
    private val tvName: () -> String?,
    /** Runs a change to the screen on the main thread. */
    private val onMain: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port), BACKLOG)
    }
    private val connections = Semaphore(MAX_CONNECTIONS)

    // Pictures are many and can be slow (the provider's server): they share a few slots of their own, so a page full of
    // posters never keeps the phone from playing something or typing.
    private val pictures = Semaphore(MAX_PICTURES)

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
            method == "GET" && path.startsWith("/api/") && !pairing.allows(query["token"]) -> respond(out, 401, JSON, ERROR_UNPAIRED)
            method == "GET" && path == "/api/events" -> streamEvents(out, query.getValue("token"))
            method == "GET" && path == "/api/art" -> {
                if (!pictures.tryAcquire(PICTURE_WAIT_MS, TimeUnit.MILLISECONDS)) return respond(out, 503, TEXT, "Busy".toByteArray())
                val picture = try {
                    runBlocking {
                        withTimeoutOrNull(
                            PICTURE_TIMEOUT_MS,
                        ) { runCatching { library.picture(query["ref"].orEmpty()) }.getOrNull() }
                    }
                } finally {
                    pictures.release()
                } ?: return respond(out, 404, TEXT, "No picture".toByteArray())
                // Pictures change rarely: the phone may keep them for a day, privately.
                respond(out, 200, picture.type, picture.bytes, cache = "private, max-age=86400")
            }
            method == "GET" && path.startsWith("/api/") -> {
                val answer = runBlocking {
                    runCatching {
                        when (path) {
                            "/api/home" -> library.home()
                            "/api/channels" -> library.group(query["group"].orEmpty())
                            "/api/search" -> library.search(query["q"].orEmpty(), query["list"]?.ifEmpty { null })
                            "/api/list" -> library.list(query["id"].orEmpty())
                            "/api/item" -> library.item(query["kind"].orEmpty(), query["id"].orEmpty())
                            "/api/movie" -> library.movie(query["id"].orEmpty())
                            "/api/series" -> library.series(query["id"].orEmpty())
                            // Films and series on the phone (2c, Design 2.16).
                            "/api/library" -> library.library(query["kind"].orEmpty())
                            "/api/browse" -> library.browse(
                                query["kind"].orEmpty(),
                                query["filter"]?.ifEmpty { null } ?: "all",
                                query["offset"]?.toIntOrNull() ?: 0,
                                query["limit"]?.toIntOrNull() ?: BROWSE_PAGE,
                            )
                            else -> null
                        }
                    }.getOrNull()
                } ?: return respond(out, 404, JSON, ERROR_NOT_FOUND)
                respond(out, 200, JSON, answer.toString().toByteArray())
            }
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
                if (field != null && field.id == body.optLong("field")) {
                    onMain { field.onDone() }
                    CompanionHub.notify(NoticeKind.TYPED)
                }
            }
            "/api/play" -> {
                val opened = runBlocking {
                    runCatching {
                        library.request(
                            body.optString("kind"),
                            body.optString("id"),
                            body.optString("scope").ifEmpty { null },
                            body.optBoolean("fromStart"),
                        )
                    }.getOrNull()
                } ?: return respond(out, 404, JSON, ERROR_NOT_FOUND)
                if (!CompanionHub.open(opened.first)) return respond(out, 409, JSON, ERROR_HIDDEN)
                CompanionHub.notify(NoticeKind.PLAYING, opened.second)
            }
            "/api/mylist" -> {
                val done = runBlocking {
                    runCatching {
                        library.setMyList(
                            body.optString("kind"),
                            body.optString("id"),
                            body.optBoolean("on"),
                        )
                    }.getOrDefault(false)
                }
                if (!done) return respond(out, 404, JSON, ERROR_NOT_FOUND)
            }
            "/api/list" -> return listChange(body, out)
            "/api/favorite" -> {
                if (body.optString("kind") != "channel") return respond(out, 400, JSON, ERROR_MISSING)
                val on = body.optBoolean("on")
                val done = runBlocking {
                    runCatching { library.setInList(CompanionLibrary.FAVOURITES, "channel", body.optString("id"), on) }.getOrNull()
                }
                if (done !is ListResult.Done) return respond(out, 404, JSON, ERROR_NOT_FOUND)
                notifyFavourite(body.optString("id"), on)
            }
            "/api/provider" -> {
                // The password and the link are read here and handed to the open form; they are never logged, stored
                // on the phone's behalf or sent back in any event (ADR-0050, step 4, the owner's option B).
                val slot = CompanionHub.providerForm.value
                if (slot == null ||
                    slot.id != body.optLong("form")
                ) {
                    return respond(out, 409, JSON, """{"error":"form-closed"}""".toByteArray())
                }
                val entry = when (body.optString("kind")) {
                    "login" -> ProviderEntry.Login(
                        body.optString("server").trim(),
                        body.optString("user").trim(),
                        body.optString("password"),
                    )
                        .takeIf { it.server.isNotEmpty() && it.user.isNotEmpty() && it.password.isNotEmpty() }
                    "link" -> ProviderEntry.Link(body.optString("url").trim()).takeIf { it.url.isNotEmpty() }
                    else -> null
                } ?: return respond(out, 400, JSON, ERROR_MISSING)
                onMain { if (CompanionHub.providerForm.value?.id == slot.id) slot.submit(entry) }
            }
            "/api/key" -> {
                val key = body.optString("key")
                val code = KEYS[key] ?: return respond(out, 400, JSON, """{"error":"unknown-key"}""".toByteArray())
                CompanionHub.press(code)
                // The arrow pad gets no note: it would show on every press.
                when (key) {
                    "playpause" -> CompanionHub.playing.value?.let {
                        CompanionHub.notify(
                            if (it.playing) NoticeKind.PAUSED else NoticeKind.RESUMED,
                        )
                    }
                    "channel-up" -> CompanionHub.notify(NoticeKind.CHANNEL_UP)
                    "channel-down" -> CompanionHub.notify(NoticeKind.CHANNEL_DOWN)
                    "last-channel" -> CompanionHub.notify(NoticeKind.LAST_CHANNEL)
                }
            }
            else -> return respond(out, 404, JSON, ERROR_NOT_FOUND)
        }
        respond(out, 200, JSON, """{"ok":true}""".toByteArray())
    }

    /**
     * The phone's changes to the viewer's lists (Design 2.8–2.12): make one (named, or "New list", "New list 2"…, with
     * its first item), rename, delete, and put things in or take them out. The TV shows a note for each (2.13).
     */
    private fun listChange(body: JSONObject, out: OutputStream) {
        val id = body.optString("id")
        val result = runBlocking {
            runCatching {
                when (body.optString("action")) {
                    "create" -> library.createList(
                        body.optString("title").ifEmpty { null },
                        body.optJSONObject("add")?.let { it.optString("kind") to it.optString("id") },
                    )
                    "rename" -> library.renameList(id, body.optString("title"))
                    "delete" -> library.deleteList(id)
                    "add" -> library.setInList(id, body.optString("kind"), body.optString("item"), on = true)
                    "remove" -> library.setInList(id, body.optString("kind"), body.optString("item"), on = false)
                    else -> ListResult.Missing
                }
            }.getOrDefault(ListResult.NotFound)
        }
        when (result) {
            is ListResult.NameTaken ->
                return respond(out, 409, JSON, JSONObject().put("error", "name-taken").put("title", result.title).toString().toByteArray())
            ListResult.NotFound -> return respond(out, 404, JSON, ERROR_NOT_FOUND)
            ListResult.Missing -> return respond(out, 400, JSON, ERROR_MISSING)
            is ListResult.Done -> {
                val title = result.title
                val item = body.optString("item")
                when (body.optString("action")) {
                    "create" -> CompanionHub.notify(NoticeKind.LIST_NEW, title)
                    "rename" -> CompanionHub.notify(NoticeKind.LIST_RENAMED, result.previous, title)
                    "delete" -> CompanionHub.notify(NoticeKind.LIST_DELETED, title)
                    "add", "remove" -> {
                        val on = body.optString("action") == "add"
                        if (result.id == CompanionLibrary.FAVOURITES) {
                            notifyFavourite(item, on)
                        } else {
                            val name = runBlocking { runCatching { library.itemName(body.optString("kind"), item) }.getOrNull() }
                            if (name != null && title != null) {
                                if (on) {
                                    CompanionHub.notifyAdded(
                                        name,
                                        title,
                                        result.id,
                                    )
                                } else {
                                    CompanionHub.notify(NoticeKind.LIST_REMOVE, name, title)
                                }
                            }
                        }
                    }
                }
                val answer = JSONObject().put(
                    "ok",
                    true,
                ).put("id", result.id).put("title", title ?: JSONObject.NULL).put("count", result.count)
                respond(out, 200, JSON, answer.toString().toByteArray())
            }
        }
    }

    private fun notifyFavourite(channel: String, on: Boolean) {
        val name = runBlocking { runCatching { library.itemName("channel", channel) }.getOrNull() } ?: return
        CompanionHub.notify(if (on) NoticeKind.FAVOURITE_ON else NoticeKind.FAVOURITE_OFF, name)
    }

    /** Tells the phone about the field under the remote and what is playing whenever they change, and that the TV is there. */
    private fun streamEvents(out: OutputStream, token: String) {
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n" +
                    "Connection: keep-alive\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
        // The TV's name first, for the phone's header (x2).
        send(out, "tv", JSONObject().put("name", tvName() ?: JSONObject.NULL).toString())
        var sentField: String? = null
        var sentPlaying: String? = null
        var sentScreen: Boolean? = null
        var sentProgress: ProviderProgress? = null
        var sentForm: String? = null
        var seenRevision = library.revision
        var sentLists = runBlocking { runCatching { library.listsStamp() }.getOrNull() }
        var lastWriteMs = 0L
        // Ends when the server stops or the viewer forgets this phone in Settings.
        while (!socket.isClosed && pairing.allows(token)) {
            val now = System.currentTimeMillis()
            val field = fieldEvent(CompanionHub.field.value)
            val playing = playingEvent(CompanionHub.playing.value)
            if (field != sentField) {
                send(out, "field", field)
                sentField = field
                lastWriteMs = now
            }
            if (playing != sentPlaying) {
                send(out, "playing", playing)
                sentPlaying = playing
                lastWriteMs = now
            }
            val screen = CompanionHub.onScreen.value
            if (screen != sentScreen) {
                send(out, "screen", JSONObject().put("on", screen).toString())
                sentScreen = screen
                lastWriteMs = now
            }
            // Progress first, so a phone sees "connected" before the form closes behind it.
            val progress = CompanionHub.providerProgress.value
            if (progress != sentProgress) {
                send(
                    out,
                    "provider-state",
                    JSONObject().put("state", progress.state).put("reason", progress.reason ?: JSONObject.NULL)
                        .put("message", progress.message ?: JSONObject.NULL).toString(),
                )
                sentProgress = progress
                lastWriteMs = now
            }
            val form = CompanionHub.providerForm.value.let { slot ->
                if (slot == null) {
                    JSONObject().put("open", false)
                } else {
                    JSONObject().put("open", true).put("kind", slot.kind).put("id", slot.id)
                }
            }.toString()
            if (form != sentForm) {
                send(out, "provider-form", form)
                sentForm = form
                lastWriteMs = now
            }
            // Lists and favourites, changed on the TV or a phone (2b): only a nudge, the phone fetches what it shows.
            val revision = library.revision
            if (revision != seenRevision) {
                seenRevision = revision
                val lists = runBlocking { runCatching { library.listsStamp() }.getOrNull() }
                if (lists != sentLists) {
                    send(out, "lists", "{}")
                    sentLists = lists
                    lastWriteMs = now
                }
            }
            if (now - lastWriteMs > HEARTBEAT_MS) {
                // A comment line: keeps the connection open through home routers and shows a dead phone as a failed write.
                out.write(": still here\n\n".toByteArray())
                out.flush()
                lastWriteMs = now
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun send(out: OutputStream, event: String, data: String) {
        out.write("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun fieldEvent(field: CompanionField?): String = JSONObject().apply {
        if (field != null) {
            put("id", field.id)
            put("label", field.label)
            put("password", field.password)
            if (field.password) put("length", field.value.length) else put("value", field.value)
        }
    }.toString()

    private fun playingEvent(playing: CompanionPlaying?): String = JSONObject().apply {
        if (playing != null) {
            put("title", playing.title)
            playing.subtitle?.let { put("subtitle", it) }
            put("live", playing.live)
            playing.channelNumber?.let { put("number", it) }
            playing.programme?.let { put("programme", it) }
            playing.programmeStartMs?.let { put("start", it) }
            playing.programmeEndMs?.let { put("end", it) }
            // Whole seconds: the position would otherwise change the event every poll and flood the phone.
            playing.positionMs?.let { put("position", it / 1000 * 1000) }
            playing.durationMs?.let { put("duration", it) }
            put("playing", playing.playing)
            playing.art?.let { put("art", it) }
            put("audio", JSONArray(playing.audio.map { JSONObject().put("id", it.id).put("label", it.label) }))
            put("subtitles", JSONArray(playing.subtitles.map { JSONObject().put("id", it.id).put("label", it.label) }))
            put("selectedAudio", playing.selectedAudio ?: JSONObject.NULL)
            put("selectedSubtitle", playing.selectedSubtitle ?: JSONObject.NULL)
        }
    }.toString()

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

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, cache: String = "no-store") {
        out.write(
            (
                "HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: $cache\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        /** A poster page on the phone: Design 2.16 asks for sixty at a time. */
        const val BROWSE_PAGE = 60
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 16
        const val MAX_PICTURES = 4
        const val PICTURE_WAIT_MS = 5_000L
        const val PICTURE_TIMEOUT_MS = 8_000L
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
        const val MAX_SEEK_MS = 60_000L
        val ERROR_HIDDEN = """{"error":"luz-hidden"}""".toByteArray()
        val ERROR_NOT_FOUND = """{"error":"not-found"}""".toByteArray()
        val ERROR_MISSING = """{"error":"missing"}""".toByteArray()
        val ERROR_UNPAIRED = """{"error":"not-paired"}""".toByteArray()
        val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            409 to "Conflict", 503 to "Service Unavailable", 411 to "Length Required", 413 to "Payload Too Large",
            421 to "Misdirected Request",
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
            "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            "channel-up" to KeyEvent.KEYCODE_CHANNEL_UP,
            "channel-down" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "last-channel" to KeyEvent.KEYCODE_LAST_CHANNEL,
        )
    }
}
