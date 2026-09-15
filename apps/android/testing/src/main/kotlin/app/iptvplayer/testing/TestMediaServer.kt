package app.iptvplayer.testing

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 serving the synthetic media of tooling/fixtures/media (packaged as assets), with
 * injectable faults. Test and debug use only: no TLS, one thread per connection, no keep-alive.
 *
 * Routes:
 * - `/vod.mp4` — 10 s progressive MP4 (supports `Range`)
 * - `/hls/vod.m3u8`, `/seg/seg-NN.ts` — 30 s HLS VOD
 * - `/hls/live.m3u8` — sliding live window over the same segments (4 segments, advances every 2 s, loops with
 *   `EXT-X-DISCONTINUITY`)
 * - `/live.ts` — continuous MPEG-TS paced in real time (the common Xtream live output)
 * - `/garbage.m3u8` — malformed HLS playlist
 * - `/noise.ts` — random bytes with a video content type
 * - `/html.ts` — an HTML error page where media was expected
 * - `/player_api.php`, `/get.php`, `/xmltv.php`, `/live/{user}/{password}/{id}.{ts|m3u8}` — a synthetic Xtream Codes
 *   panel ([TestPanel]) that accepts only [TestPanel.USERNAME] / [TestPanel.PASSWORD]
 * - `/redirect` — 302 to `/vod.mp4`
 * - anything else — 404
 */
class TestMediaServer(private val assets: AssetManager, port: Int = 0) : AutoCloseable {
    private val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    private val startedAtMs = System.currentTimeMillis()
    private val failures = ConcurrentHashMap<String, Pair<Int, AtomicInteger>>()
    private val requests = CopyOnWriteArrayList<String>()

    /** While true, the live playlist stops advancing and new segment or `/live.ts` data is withheld. */
    @Volatile
    var stalled: Boolean = false

    /** Delay before any response headers are sent. */
    @Volatile
    var responseDelayMs: Long = 0

    val port: Int get() = socket.localPort

    init {
        thread(name = "test-media-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                thread(name = "test-media-client", isDaemon = true) { client.use { handle(it) } }
            }
        }
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    /** Responds to the next [count] requests whose path starts with [pathPrefix] with [status]. */
    fun failNext(pathPrefix: String, status: Int, count: Int = 1) {
        failures[pathPrefix] = status to AtomicInteger(count)
    }

    /** Paths requested so far, in order (query strings included). */
    fun requestedPaths(): List<String> = requests.toList()

    override fun close() {
        socket.close()
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val path = requestLine.split(' ').getOrNull(1) ?: return
        requests += path
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
        val out = client.getOutputStream()
        try {
            injectedFailure(path)?.let { status ->
                respond(out, status, "text/plain", "injected $status".toByteArray())
                return
            }
            val route = path.substringBefore('?')
            val query = path.substringAfter('?', "")
            when {
                route == "/player_api.php" -> respond(out, 200, "application/json", TestPanel.api(query).toByteArray())
                route == "/get.php" -> TestPanel.m3u(query, url(""))?.let { respond(out, 200, "audio/x-mpegurl", it.toByteArray()) }
                    ?: respond(out, 401, "text/plain", "unauthorized".toByteArray())
                route == "/xmltv.php" -> TestPanel.xmltv(query)?.let { respond(out, 200, "application/xml", it.toByteArray()) }
                    ?: respond(out, 401, "text/plain", "unauthorized".toByteArray())
                route.startsWith("/live/") -> when (TestPanel.liveTarget(route)) {
                    TestPanel.Target.TS -> streamLiveTs(out)
                    TestPanel.Target.HLS -> respond(out, 200, HLS, livePlaylist())
                    null -> respond(out, 404, "text/plain", "not found".toByteArray())
                }
                route == "/redirect" -> respond(out, 302, "text/plain", ByteArray(0), "Location: /vod.mp4\r\n")
                route == "/vod.mp4" -> serveAsset(out, "vod-10s.mp4", "video/mp4", headers["range"])
                route == "/hls/vod.m3u8" -> respond(out, 200, HLS, vodPlaylist())
                route == "/hls/live.m3u8" -> respond(out, 200, HLS, livePlaylist())
                route.startsWith("/seg/") -> serveSegment(out, route.removePrefix("/seg/"))
                route == "/live.ts" -> streamLiveTs(out)
                route == "/garbage.m3u8" -> respond(out, 200, HLS, "#EXTM3U\n#EXT-X-TARGETDURATION:two\n#EXTINF:?,\n\u0000\n".toByteArray())
                route == "/noise.ts" -> respond(
                    out,
                    200,
                    "video/mp2t",
                    ByteArray(256 * 1024).also { kotlin.random.Random(7).nextBytes(it) },
                )
                route == "/html.ts" -> respond(out, 200, "text/html", "<html><body>Account disabled</body></html>".toByteArray())
                else -> respond(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (_: SocketException) {
            // Client went away (player released or seeking); nothing to do.
        }
    }

    private fun injectedFailure(path: String): Int? {
        for ((prefix, failure) in failures) {
            if (path.startsWith(prefix) && failure.second.getAndDecrement() > 0) return failure.first
        }
        return null
    }

    private fun vodPlaylist(): ByteArray = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        for (i in 0 until SEGMENTS) append("#EXTINF:2.000,\n/seg/${segmentName(i)}\n")
        append("#EXT-X-ENDLIST\n")
    }.toByteArray()

    private var frozenLiveIndex = -1L

    /** Live window: the newest available segment is `elapsed / 2 s + 3`, so three segments exist at start. */
    @Synchronized
    private fun livePlaylist(): ByteArray {
        val computed = (System.currentTimeMillis() - startedAtMs) / SEGMENT_MS + 3
        val newest = if (stalled) {
            (
                if (frozenLiveIndex <
                    0
                ) {
                    computed.also { frozenLiveIndex = it }
                } else {
                    frozenLiveIndex
                }
                )
        } else {
            computed.also { frozenLiveIndex = -1 }
        }
        val first = maxOf(0, newest - WINDOW + 1)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
            append("#EXT-X-MEDIA-SEQUENCE:$first\n#EXT-X-DISCONTINUITY-SEQUENCE:${first / SEGMENTS}\n")
            for (sequence in first..newest) {
                if (sequence > 0 && sequence % SEGMENTS == 0L) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:2.000,\n/seg/${segmentName((sequence % SEGMENTS).toInt())}?live=$sequence\n")
            }
        }.toByteArray()
    }

    private fun serveSegment(out: OutputStream, name: String) {
        if (!SEGMENT_NAME.matches(name)) return respond(out, 404, "text/plain", "not found".toByteArray())
        while (stalled) Thread.sleep(100)
        serveAsset(out, "segments/$name", "video/mp2t", null)
    }

    /** Sends the first segment at once (like real servers' initial burst), then paces the rest at real time. */
    private fun streamLiveTs(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
        val start = System.currentTimeMillis()
        var index = 0
        while (true) {
            while (stalled) Thread.sleep(100)
            val data = assets.open("segments/${segmentName(index % SEGMENTS)}").use { it.readBytes() }
            val dueAt = start + index * SEGMENT_MS
            val chunks = 10
            for (c in 0 until chunks) {
                val wait = dueAt + c * (SEGMENT_MS / chunks) - System.currentTimeMillis()
                if (index > 0 && wait > 0) Thread.sleep(wait)
                val from = data.size * c / chunks
                val to = data.size * (c + 1) / chunks
                out.write(data, from, to - from)
            }
            out.flush()
            index++
        }
    }

    private fun serveAsset(out: OutputStream, asset: String, contentType: String, range: String?) {
        val data = assets.open(asset).use { it.readBytes() }
        val match = range?.let { RANGE.matchEntire(it) }
        if (match == null) return respond(out, 200, contentType, data)
        val from = match.groupValues[1].toInt()
        val to = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(data.size - 1) ?: (data.size - 1)
        if (from >= data.size) return respond(out, 416, "text/plain", ByteArray(0))
        val body = data.copyOfRange(from, to + 1)
        respond(out, 206, contentType, body, "Content-Range: bytes $from-$to/${data.size}\r\n")
    }

    private fun respond(out: OutputStream, status: Int, contentType: String, body: ByteArray, extraHeaders: String = "") {
        val head = "HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Accept-Ranges: bytes\r\n${extraHeaders}Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private companion object {
        const val SEGMENTS = 15
        const val WINDOW = 4
        const val SEGMENT_MS = 2_000L
        const val HLS = "application/vnd.apple.mpegurl"
        val SEGMENT_NAME = Regex("seg-\\d{2}\\.ts")
        val RANGE = Regex("bytes=(\\d+)-(\\d*)")
        val REASONS = mapOf(
            200 to "OK",
            302 to "Found",
            206 to "Partial Content",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            416 to "Range Not Satisfiable",
            503 to "Service Unavailable",
        )

        fun segmentName(index: Int) = "seg-%02d.ts".format(index)
    }
}
