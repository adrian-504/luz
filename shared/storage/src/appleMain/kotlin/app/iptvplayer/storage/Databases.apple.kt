package app.iptvplayer.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.iptvplayer.storage.db.IptvDatabase
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag

public actual fun openIptvDatabase(path: String): SqlDriver {
    val inMemory = path == ":memory:"
    val name = if (inMemory) "iptv-memory" else path.substringAfterLast('/')
    val directory = if (inMemory) null else path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null }
    return NativeSqliteDriver(
        schema = IptvDatabase.Schema,
        name = name,
        onConfiguration = { configuration ->
            configuration.copy(
                inMemory = inMemory,
                journalMode = JournalMode.WAL,
                extendedConfig = configuration.extendedConfig.copy(
                    basePath = directory,
                    foreignKeyConstraints = true,
                    synchronousFlag = SynchronousFlag.NORMAL,
                ),
            )
        },
    )
}

internal actual val CHECKPOINT_MODE: String = "PASSIVE"
