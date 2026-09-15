package app.iptvplayer.protocols.io

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.ports.ByteSource

/** Thrown by decompressing sources for corrupt or truncated compressed data. Carries no content. */
internal class CorruptCompressedDataException : Exception("Corrupt or truncated compressed data")

/** Thrown when a decompression limit is exceeded (gzip-bomb protection, docs/SECURITY.md §6). */
internal class DecompressionLimitException(val limit: LimitKind) : Exception("Decompression limit exceeded: $limit")

/** gzip decompression over the platform zlib (java.util.zip on JVM/Android, libz on Apple). */
internal expect fun gunzip(compressed: ByteSource): ByteSource

/** Magic bytes `1f 8b`. */
internal fun isGzip(prefix: ByteArray): Boolean = prefix.size >= 2 && prefix[0] == 0x1F.toByte() && prefix[1] == 0x8B.toByte()

/** Counts bytes read from [source] and enforces [maxBytes] on the compressed input. */
internal class CountingSource(private val source: ByteSource, private val maxBytes: Long) : ByteSource {
    var count: Long = 0
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = source.read(buffer, offset, length)
        if (read > 0) {
            count += read
            if (count > maxBytes) throw DecompressionLimitException(LimitKind.DOWNLOAD_SIZE)
        }
        return read
    }

    override fun close() = source.close()
}

/**
 * Wraps a decompressed stream: total output is capped, and once output passes [ratioThresholdBytes] the
 * output/input ratio must stay within [maxRatio].
 */
internal class DecompressionLimits(
    private val decompressed: ByteSource,
    private val compressed: CountingSource,
    private val maxOutputBytes: Long,
    private val maxRatio: Int?,
    private val ratioThresholdBytes: Long = 64L * 1024 * 1024,
) : ByteSource {
    private var output = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = decompressed.read(buffer, offset, length)
        if (read > 0) {
            output += read
            if (output > maxOutputBytes) throw DecompressionLimitException(LimitKind.DECOMPRESSED_SIZE)
            if (maxRatio != null && output > ratioThresholdBytes && output > compressed.count.coerceAtLeast(1) * maxRatio) {
                throw DecompressionLimitException(LimitKind.COMPRESSION_RATIO)
            }
        }
        return read
    }

    override fun close() = decompressed.close()
}
