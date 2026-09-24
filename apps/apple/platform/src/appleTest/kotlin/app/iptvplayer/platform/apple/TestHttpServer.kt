package app.iptvplayer.platform.apple

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.usleep

/**
 * A one-thread HTTP/1.1 server on 127.0.0.1 for the transport tests: enough to answer the routes below, nothing more.
 * Every answer closes the connection, so there is no keep-alive to reason about.
 */
@OptIn(ExperimentalForeignApi::class)
class TestHttpServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0)
    val port: Int

    /** The request headers of the last request to `/headers`, as the server received them. */
    var lastHeaders: String = ""
        private set

    init {
        memScoped {
            val one = alloc<kotlinx.cinterop.IntVar>().apply { value = 1 }
            setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<kotlinx.cinterop.IntVar>().convert())
            val address = alloc<sockaddr_in>().apply {
                sin_len = sizeOf<sockaddr_in>().convert()
                sin_family = AF_INET.convert()
                sin_port = 0u
                sin_addr.s_addr = swap(INADDR_LOOPBACK)
            }
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
            check(listen(listener, 16) == 0) { "listen failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(listener, address.ptr.reinterpret(), length.ptr)
            val raw = address.sin_port.toInt()
            port = ((raw and 0xFF) shl 8) or ((raw shr 8) and 0xFF)
        }
        scope.launch { serve() }
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun stop() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
        scope.cancel()
    }

    private fun serve() {
        while (true) {
            val connection = accept(listener, null, null)
            if (connection < 0) return
            try {
                answer(connection, readHead(connection))
            } finally {
                close(connection)
            }
        }
    }

    private fun readHead(connection: Int): String {
        val head = StringBuilder()
        val buffer = ByteArray(1024)
        while (!head.endsWith("\r\n\r\n")) {
            val count = buffer.usePinned { recv(connection, it.addressOf(0), buffer.size.convert(), 0) }
            if (count <= 0) break
            head.append(buffer.decodeToString(0, count.toInt()))
        }
        return head.toString()
    }

    private fun answer(connection: Int, head: String) {
        val path = head.substringAfter(' ').substringBefore(' ')
        when (path) {
            "/ok" -> reply(connection, 200, "OK", "hello luz".encodeToByteArray(), "X-Test: yes\r\n")
            "/big" -> reply(connection, 200, "OK", ByteArray(BIG) { ('a'.code + it % 26).toByte() })
            "/redirect" -> reply(connection, 302, "Found", ByteArray(0), "Location: /ok\r\n")
            "/missing" -> reply(connection, 404, "Not Found", ByteArray(0))
            "/slow" -> {
                usleep(2_000_000u)
                reply(connection, 200, "OK", "late".encodeToByteArray())
            }
            "/headers" -> {
                lastHeaders = head
                reply(connection, 200, "OK", ByteArray(0))
            }
            else -> reply(connection, 404, "Not Found", ByteArray(0))
        }
    }

    private fun reply(connection: Int, status: Int, reason: String, body: ByteArray, extra: String = "") {
        val head = "HTTP/1.1 $status $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n"
        write(connection, head.encodeToByteArray())
        write(connection, body)
    }

    private fun write(connection: Int, bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val count = bytes.usePinned { send(connection, it.addressOf(sent), (bytes.size - sent).convert(), 0) }
            if (count <= 0) return
            sent += count.toInt()
        }
    }

    private fun swap(value: UInt): UInt =
        ((value and 0xFFu) shl 24) or ((value and 0xFF00u) shl 8) or ((value shr 8) and 0xFF00u) or (value shr 24)

    companion object {
        const val BIG = 100_000
    }
}
