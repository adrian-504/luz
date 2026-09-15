package app.iptvplayer.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLDelight [SqlDriver] over AndroidX's bundled SQLite (ADR-0025): the same SQLite build with FTS5 on Android and in JVM
 * tests, independent of the device's framework SQLite. One connection in WAL mode; calls are serialized with a lock, and
 * a transaction holds the lock until it ends, so a background import and UI reads never interleave inside a transaction.
 */
public class BundledSqliteDriver private constructor(private val connection: SQLiteConnection) : SqlDriver {
    private val lock = ReentrantLock()
    private val currentTransaction = ThreadLocal<Transaction?>()
    private val statements = object : LinkedHashMap<Int, SQLiteStatement>(STATEMENT_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SQLiteStatement>): Boolean =
            (size > STATEMENT_CACHE_SIZE).also { if (it) eldest.value.close() }
    }
    private val listeners = HashMap<String, MutableSet<Query.Listener>>()

    public companion object {
        private const val STATEMENT_CACHE_SIZE = 64

        /** Opens (creating or migrating) the database at [path]; `:memory:` opens a private in-memory database. */
        public fun open(path: String, schema: SqlSchema<QueryResult.Value<Unit>>): BundledSqliteDriver {
            val driver = BundledSqliteDriver(BundledSQLiteDriver().open(path))
            driver.pragma("PRAGMA journal_mode=WAL")
            driver.pragma("PRAGMA synchronous=NORMAL")
            driver.pragma("PRAGMA foreign_keys=ON")
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            }, 0).value
            when {
                version == 0L -> schema.create(driver)
                version < schema.version -> schema.migrate(driver, version, schema.version)
            }
            if (version != schema.version) driver.execute(null, "PRAGMA user_version=${schema.version}", 0)
            return driver
        }
    }

    private fun pragma(sql: String) {
        lock.withLock { connection.prepare(sql).use { drain(it) } }
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        lock.withLock {
            withStatement(identifier, sql) { statement ->
                binders?.invoke(Binder(statement))
                drain(statement)
            }
            QueryResult.Value(withStatement(CHANGES_ID, "SELECT changes()") { if (it.step()) it.getLong(0) else 0L })
        }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = lock.withLock {
        withStatement(identifier, sql) { statement ->
            binders?.invoke(Binder(statement))
            mapper(Cursor(statement))
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        lock.lock()
        val enclosing = currentTransaction.get()
        if (enclosing == null) {
            try {
                connection.prepare("BEGIN IMMEDIATE").use { it.step() }
            } catch (e: Exception) {
                lock.unlock()
                throw e
            }
        }
        return QueryResult.Value(Transaction(enclosing).also { currentTransaction.set(it) })
    }

    override fun currentTransaction(): Transacter.Transaction? = currentTransaction.get()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it) { LinkedHashSet() }.add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = synchronized(listeners) { queryKeys.flatMapTo(LinkedHashSet()) { listeners[it].orEmpty() } }
        toNotify.forEach { it.queryResultsChanged() }
    }

    override fun close() {
        lock.withLock {
            statements.values.forEach { it.close() }
            statements.clear()
            connection.close()
        }
    }

    private inline fun <T> withStatement(identifier: Int?, sql: String, block: (SQLiteStatement) -> T): T {
        if (identifier == null) return connection.prepare(sql).use(block)
        val statement = statements.getOrPut(identifier) { connection.prepare(sql) }
        try {
            return block(statement)
        } finally {
            statement.reset()
            statement.clearBindings()
        }
    }

    private inner class Transaction(override val enclosingTransaction: Transaction?) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            try {
                if (enclosingTransaction == null) {
                    connection.prepare(if (successful) "COMMIT" else "ROLLBACK").use { it.step() }
                }
            } finally {
                currentTransaction.set(enclosingTransaction)
                lock.unlock()
            }
            return QueryResult.Unit
        }
    }

    /** SQLDelight indexes parameters from 0; SQLite from 1. */
    private class Binder(private val statement: SQLiteStatement) : SqlPreparedStatement {
        override fun bindBytes(index: Int, bytes: ByteArray?) = if (bytes ==
            null
        ) {
            statement.bindNull(index + 1)
        } else {
            statement.bindBlob(index + 1, bytes)
        }

        override fun bindLong(index: Int, long: Long?) = if (long ==
            null
        ) {
            statement.bindNull(index + 1)
        } else {
            statement.bindLong(index + 1, long)
        }

        override fun bindDouble(index: Int, double: Double?) =
            if (double == null) statement.bindNull(index + 1) else statement.bindDouble(index + 1, double)

        override fun bindString(index: Int, string: String?) =
            if (string == null) statement.bindNull(index + 1) else statement.bindText(index + 1, string)

        override fun bindBoolean(index: Int, boolean: Boolean?) =
            if (boolean == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, if (boolean) 1L else 0L)
    }

    private class Cursor(private val statement: SQLiteStatement) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(statement.step())

        override fun getString(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)

        override fun getLong(index: Int): Long? = if (statement.isNull(index)) null else statement.getLong(index)

        override fun getBytes(index: Int): ByteArray? = if (statement.isNull(index)) null else statement.getBlob(index)

        override fun getDouble(index: Int): Double? = if (statement.isNull(index)) null else statement.getDouble(index)

        override fun getBoolean(index: Int): Boolean? = if (statement.isNull(index)) null else statement.getLong(index) != 0L
    }
}

private const val CHANGES_ID = Int.MIN_VALUE

private fun drain(statement: SQLiteStatement) {
    @Suppress("ControlFlowWithEmptyBody")
    while (statement.step()) {
    }
}
