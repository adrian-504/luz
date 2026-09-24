package app.iptvplayer.ingestion

import java.io.File

internal actual fun temporaryDatabasePath(prefix: String): String = File.createTempFile(prefix, ".db").also { it.delete() }.path

internal actual fun readFileAsLatin1(path: String): String =
    File(path).takeIf { it.exists() }?.let { String(it.readBytes(), Charsets.ISO_8859_1) }.orEmpty()

internal actual fun deleteFile(path: String) {
    File(path).delete()
}
