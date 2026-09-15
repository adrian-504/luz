package app.iptvplayer.protocols.io

import app.iptvplayer.domain.ports.ByteSource
import java.io.EOFException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

internal actual fun gunzip(compressed: ByteSource): ByteSource = JvmGzipSource(compressed)

private class JvmGzipSource(private val compressed: ByteSource) : ByteSource {
    private val input = object : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int = compressed.read(b, off, len)
    }
    private var stream: GZIPInputStream? = null

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val gzip = stream ?: GZIPInputStream(input, 64 * 1024).also { stream = it }
        gzip.read(buffer, offset, length)
    } catch (_: ZipException) {
        throw CorruptCompressedDataException()
    } catch (_: EOFException) {
        throw CorruptCompressedDataException()
    }

    override fun close() {
        stream?.close() ?: compressed.close()
    }
}
