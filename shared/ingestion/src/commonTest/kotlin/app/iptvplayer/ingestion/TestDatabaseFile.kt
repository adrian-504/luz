package app.iptvplayer.ingestion

/** A database file in the platform's temporary directory, for one test; [delete] removes it and its WAL and SHM files. */
class TestDatabaseFile(prefix: String) {
    val path: String = temporaryDatabasePath(prefix)

    /** The database file and its write-ahead log as Latin-1 text, to check what was written to disk. */
    fun contents(): String = listOf("", "-wal").joinToString("") { readFileAsLatin1(path + it) }

    fun delete() {
        listOf("", "-wal", "-shm").forEach { deleteFile(path + it) }
    }
}

internal expect fun temporaryDatabasePath(prefix: String): String

/** The file's bytes, one character per byte; empty when there is no such file. */
internal expect fun readFileAsLatin1(path: String): String

internal expect fun deleteFile(path: String)
