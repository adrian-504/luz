package app.iptvplayer.protocols.io

import app.iptvplayer.domain.ports.ByteSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// NOT YET VERIFIED: compiled only when Xcode is installed (iptv.appleTargets). JVM gzip tests define the expected behavior.
@OptIn(ExperimentalForeignApi::class)
internal actual fun gunzip(compressed: ByteSource): ByteSource = AppleGzipSource(compressed)

@OptIn(ExperimentalForeignApi::class)
private class AppleGzipSource(private val compressed: ByteSource) : ByteSource {
    private val stream = nativeHeap.alloc<z_stream>()
    private val input = ByteArray(64 * 1024)
    private val pinnedInput = input.pin()
    private var finished = false
    private var closed = false

    init {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.next_in = null
        stream.avail_in = 0u
        // windowBits 15 + 32: automatic zlib/gzip header detection.
        if (inflateInit2_(stream.ptr, 15 + 32, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
            nativeHeap.free(stream)
            pinnedInput.unpin()
            throw CorruptCompressedDataException()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (length == 0) return 0
        return buffer.usePinned { out ->
            stream.next_out = out.addressOf(offset).reinterpret()
            stream.avail_out = length.toUInt()
            while (true) {
                if (stream.avail_in == 0u) {
                    val read = compressed.read(input, 0, input.size)
                    if (read < 0) throw CorruptCompressedDataException()
                    stream.next_in = pinnedInput.addressOf(0).reinterpret()
                    stream.avail_in = read.toUInt()
                }
                val rc = inflate(stream.ptr, Z_NO_FLUSH)
                val produced = length - stream.avail_out.toInt()
                when (rc) {
                    Z_STREAM_END -> {
                        finished = true
                        return@usePinned if (produced > 0) produced else -1
                    }
                    Z_OK, Z_BUF_ERROR -> if (produced > 0) return@usePinned produced
                    else -> throw CorruptCompressedDataException()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
        pinnedInput.unpin()
        compressed.close()
    }
}
