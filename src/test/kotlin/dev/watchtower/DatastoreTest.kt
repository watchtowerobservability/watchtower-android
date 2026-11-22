package dev.watchtower

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class DatastoreTest {
    private val got = mutableListOf<Event>()
    private var clock = 0L
    private var main = false

    @Before
    fun setUp() {
        Datastore.sink = { got.add(it) }
        Datastore.now = { clock }
        Datastore.onMainThread = { main }
        Datastore.slowMs = 100.0
    }

    @After
    fun tearDown() {
        Datastore.sink = { Watchtower.record(it) }
        Datastore.now = System::nanoTime
        Datastore.onMainThread = { false }
    }

    private fun tick(ms: Long) { clock += ms * 1_000_000 }

    /** An interface fake: `handler(method name)` answers, everything else returns a zero value. */
    private inline fun <reified T> fake(noinline handler: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            handler(m.name) ?: when (m.returnType) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        } as T

    @Test
    fun summaryKeepsOperationAndTableOnly() {
        assertEquals("SELECT" to "users", Datastore.querySummary("select * from \"users\" where email = 'a@b'"))
        assertEquals("INSERT" to "main.orders", Datastore.querySummary("INSERT INTO main.orders VALUES (1)"))
        assertEquals("QUERY" to null, Datastore.querySummary(null))
    }

    @Test
    fun onlySlowOrFailedOpsAndMainThreadAgainstOneFrame() {
        assertNull(Datastore.event("sqlite", "SELECT", null, null, 50.0, mainThread = false))
        val e = Datastore.event("mmkv", "encode", null, "prefs", 20.0, mainThread = true)!!
        assertEquals("db", e.type)
        assertEquals(20.0, e.value)
        assertEquals(true, e.attrs["db.main_thread"])
        assertEquals("prefs", e.attrs["db.namespace"])
        assertEquals("X", Datastore.event("realm", "write", null, null, 1.0, false, "X")!!.attrs["error.type"])
    }

    @Test
    fun roomFactoryTimesQueriesAndCompiledStatementsWithoutText() {
        main = true
        val cursor = fake<Cursor> { if (it == "getCount") { tick(30); 1 } else null }
        val stmt = fake<SupportSQLiteStatement> { if (it == "executeInsert") { tick(150); 7L } else null }
        val db = fake<SupportSQLiteDatabase> {
            when (it) {
                "query" -> cursor
                "compileStatement" -> stmt
                "execSQL" -> throw SQLiteConstraintException("UNIQUE constraint failed: users.email")
                else -> null
            }
        }
        val helper = fake<SupportSQLiteOpenHelper> { if (it == "getWritableDatabase") db else null }
        // what WatchtowerSQLiteOpenHelperFactory.create returns (a real Configuration needs a Context)
        val w = WtOpenHelper(helper, "app.db").writableDatabase
        w.query("SELECT * FROM users WHERE email = 'secret@x'")
        assertEquals(7L, w.compileStatement("INSERT INTO orders VALUES (?)").executeInsert())
        assertThrows(SQLiteConstraintException::class.java) { w.execSQL("DELETE FROM users") }
        assertEquals(listOf("SELECT users", "INSERT orders", "DELETE users"), got.map { it.name })
        assertEquals(30.0, got[0].value)
        assertEquals(true, got[0].attrs["db.main_thread"])
        assertEquals("app.db", got[0].attrs["db.namespace"])
        assertEquals("SQLiteConstraintException", got[2].attrs["error.type"])
        assertFalse(got.any { it.toString().contains("secret") })
    }

    @Test
    fun storeSizeIncludesWal() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wt-ds-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, "app.db").apply { writeBytes(ByteArray(4096)) }
        File(dir, "app.db-wal").writeBytes(ByteArray(10))
        Datastore.recordStoreSize("sqlite", f)
        assertEquals(listOf("wt.embedded.file_size" to 4096.0, "wt.embedded.wal_size" to 10.0), got.map { it.name to it.value })
        dir.deleteRecursively()
    }
}
