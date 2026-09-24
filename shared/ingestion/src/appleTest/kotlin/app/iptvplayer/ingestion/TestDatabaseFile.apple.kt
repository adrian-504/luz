package app.iptvplayer.ingestion

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun temporaryDatabasePath(prefix: String): String =
    NSTemporaryDirectory().trimEnd('/') + "/" + prefix + "-" + NSUUID().UUIDString + ".db"

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFileAsLatin1(path: String): String {
    val data = NSData.dataWithContentsOfFile(path) ?: return ""
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return buildString(bytes.size) { bytes.forEach { append((it.toInt() and 0xFF).toChar()) } }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}
