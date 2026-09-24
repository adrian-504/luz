package app.iptvplayer.storage

import app.cash.sqldelight.db.SqlDriver
import app.iptvplayer.storage.db.IptvDatabase

public actual fun openIptvDatabase(path: String): SqlDriver = BundledSqliteDriver.open(path, IptvDatabase.Schema)

internal actual val CHECKPOINT_MODE: String = "TRUNCATE"
