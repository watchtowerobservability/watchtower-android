package dev.watchtower

import android.content.ContentValues
import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement

/**
 * SQLite capture for Room, SQLDelight's Android driver and raw SupportSQLite (androidx.sqlite is a
 * compileOnly dependency: apps without it never load this class). Wrap the factory you already use:
 *
 * ```
 * Room.databaseBuilder(ctx, AppDb::class.java, "app.db")
 *     .openHelperFactory(WatchtowerSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
 * AndroidSqliteDriver(Schema, ctx, "app.db", factory = WatchtowerSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
 * ```
 *
 * Every statement is timed through [Datastore.measure]: slow or failed ones become `db` events
 * (operation and first table only), main-thread ones over one frame are flagged `db.main_thread`.
 */
class WatchtowerSQLiteOpenHelperFactory(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        WtOpenHelper(delegate.create(configuration), configuration.name)
}

internal class WtOpenHelper(private val d: SupportSQLiteOpenHelper, private val name: String?) : SupportSQLiteOpenHelper by d {
    @Volatile private var w: WtDatabase? = null
    @Volatile private var r: WtDatabase? = null

    /** One wrapper per underlying database object, so repeated reads do not allocate. */
    private fun wrap(db: SupportSQLiteDatabase, cached: WtDatabase?): WtDatabase =
        if (cached?.d === db) cached else WtDatabase(db, name)

    override val writableDatabase: SupportSQLiteDatabase get() = wrap(d.writableDatabase, w).also { w = it }
    override val readableDatabase: SupportSQLiteDatabase get() = wrap(d.readableDatabase, r).also { r = it }
}

private class WtDatabase(val d: SupportSQLiteDatabase, private val name: String?) : SupportSQLiteDatabase by d {
    private inline fun <T> timed(sql: String?, body: () -> T): T {
        val (op, table) = Datastore.querySummary(sql)
        return Datastore.measure("sqlite", op, table, name, body)
    }

    private inline fun <T> op(op: String, table: String, body: () -> T): T = Datastore.measure("sqlite", op, table, name, body)

    // A cursor runs its query on the first move, which counts every row; asking for the count here
    // does that same work inside the timed call, so the app's first move costs nothing extra.
    private fun Cursor.filled(): Cursor = also { it.count }

    override fun query(query: String): Cursor = timed(query) { d.query(query).filled() }
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = timed(query) { d.query(query, bindArgs).filled() }
    override fun query(query: SupportSQLiteQuery): Cursor = timed(query.sql) { d.query(query).filled() }
    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        timed(query.sql) { d.query(query, cancellationSignal).filled() }

    override fun execSQL(sql: String) = timed(sql) { d.execSQL(sql) }
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = timed(sql) { d.execSQL(sql, bindArgs) }

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
        op("INSERT", table) { d.insert(table, conflictAlgorithm, values) }
    override fun update(
        table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?,
    ): Int = op("UPDATE", table) { d.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        op("DELETE", table) { d.delete(table, whereClause, whereArgs) }

    /** Room and SQLDelight write through compiled statements: timed when executed, not when compiled. */
    override fun compileStatement(sql: String): SupportSQLiteStatement {
        val (op, table) = Datastore.querySummary(sql)
        return WtStatement(d.compileStatement(sql), op, table, name)
    }
}

private class WtStatement(
    private val s: SupportSQLiteStatement, private val op: String, private val table: String?, private val name: String?,
) : SupportSQLiteStatement by s {
    private inline fun <T> t(body: () -> T): T = Datastore.measure("sqlite", op, table, name, body)
    override fun execute() = t { s.execute() }
    override fun executeUpdateDelete(): Int = t { s.executeUpdateDelete() }
    override fun executeInsert(): Long = t { s.executeInsert() }
    override fun simpleQueryForLong(): Long = t { s.simpleQueryForLong() }
    override fun simpleQueryForString(): String? = t { s.simpleQueryForString() }
}
