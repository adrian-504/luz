package app.iptvplayer.tv

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0013 open item: which full-text search features the Android framework SQLite provides on this device. The test
 * records facts in logcat (tag `SqliteProbe`) and never fails on a missing feature: the result decides whether Android
 * needs a bundled SQLite, and that decision is recorded in docs, not hidden in a test.
 */
@RunWith(AndroidJUnit4::class)
class PlatformSqliteProbeTest {
    private fun supported(db: SQLiteDatabase, sql: String): Boolean = runCatching { db.execSQL(sql) }.isSuccess

    @Test
    fun recordFrameworkSqliteSearchFeatures() {
        val db = SQLiteDatabase.create(null)
        try {
            val version = db.rawQuery("SELECT sqlite_version()", null).use {
                it.moveToFirst()
                it.getString(0)
            }
            val facts = linkedMapOf(
                "fts4" to supported(db, "CREATE VIRTUAL TABLE p_fts4 USING fts4(title)"),
                "fts5" to supported(db, "CREATE VIRTUAL TABLE p_fts5 USING fts5(title)"),
                "fts5_unicode61_remove_diacritics_2" to
                    supported(db, "CREATE VIRTUAL TABLE p_fts5_u USING fts5(title, tokenize='unicode61 remove_diacritics 2')"),
                "fts5_contentless_delete" to
                    supported(db, "CREATE VIRTUAL TABLE p_fts5_cd USING fts5(title, content='', contentless_delete=1)"),
            )
            Log.i("SqliteProbe", "api=${Build.VERSION.SDK_INT} sqlite=$version $facts")
        } finally {
            db.close()
        }
    }
}
