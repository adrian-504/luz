package app.iptvplayer.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Opens (creating or migrating) Luz's database at [path], with the same settings on every platform: write-ahead logging,
 * normal synchronisation and foreign keys on. `:memory:` opens a private in-memory database.
 *
 * Android and the JVM use the bundled SQLite of ADR-0025; Apple platforms use the system SQLite through SQLDelight's
 * native driver (ADR-0013).
 */
public expect fun openIptvDatabase(path: String): SqlDriver

/**
 * Folds the write-ahead log back into the database (ADR-0029). The pragma answers with a row, so it is run as a query:
 * SQLite on Apple refuses to execute a statement that returns rows, where Android's driver ignored them.
 */
internal fun SqlDriver.checkpoint() {
    executeQuery(null, "PRAGMA wal_checkpoint($CHECKPOINT_MODE)", { cursor -> QueryResult.Value(cursor.next().value) }, 0)
}

/**
 * TRUNCATE on Android, where it was measured to matter (a 6x slower read after an import on the reference TV). PASSIVE on
 * Apple: the native driver keeps a separate reader connection, and TRUNCATE waited about a second for it on every import;
 * PASSIVE folds in what it can without waiting, and SQLite's own automatic checkpoints do the rest.
 */
internal expect val CHECKPOINT_MODE: String
