package dev.watchtower

import android.os.Looper
import java.io.File

/**
 * Client storage instrumentation (embedded stores). Only slow or failed operations are recorded, as
 * RUM `db` events (value = duration ms) with OTel `db.*` attributes; statement text and values are
 * never sent. Work on the main thread blocks the UI (jank, then ANRs): it is judged against one
 * frame (16 ms) and flagged `db.main_thread`.
 *
 * - Room, SQLDelight and raw SupportSQLite: [WatchtowerSQLiteOpenHelperFactory].
 * - DataStore, MMKV, Realm and anything else: [measure] / [measureSuspend].
 * - File sizes: [recordStoreSize].
 */
object Datastore {
    /** Operations off the main thread at or over this many ms are reported (default 100). */
    @JvmStatic var slowMs = 100.0
    internal const val FRAME_MS = 16.0
    /** Seams for tests. */
    internal var sink: (Event) -> Unit = { Watchtower.record(it) }
    internal var now: () -> Long = System::nanoTime
    internal var onMainThread: () -> Boolean = { Looper.getMainLooper()?.isCurrentThread == true }

    /** The `db` event for one operation, or null when it was fast and succeeded. Pure. */
    @Suppress("LongParameterList")
    internal fun event(
        system: String, operation: String, collection: String?, namespace: String?, ms: Double,
        mainThread: Boolean, error: String? = null,
    ): Event? {
        val limit = if (mainThread) minOf(slowMs, FRAME_MS) else slowMs
        if (error == null && ms < limit) return null
        val summary = listOfNotNull(operation, collection).joinToString(" ")
        val a = mutableMapOf<String, Any>(
            "db.system.name" to system, "db.operation.name" to operation, "db.query.summary" to summary,
        )
        collection?.let { a["db.collection.name"] = it }
        namespace?.let { a["db.namespace"] = it }
        if (mainThread) a["db.main_thread"] = true
        // the class only: messages can quote values (a failed constraint, a key)
        error?.let { a["error.type"] = it }
        return Event("db", summary, value = Math.round(ms).toDouble(), attrs = a)
    }

    @PublishedApi
    @Suppress("LongParameterList")
    internal fun report(
        system: String, operation: String, collection: String?, namespace: String?, t0: Long,
        mainThread: Boolean, error: Throwable? = null,
    ) = runCatching {
        val ms = (now() - t0) / 1e6
        event(system, operation, collection, namespace, ms, mainThread, error?.javaClass?.simpleName)?.let(sink)
    }

    @PublishedApi internal fun start(): Pair<Long, Boolean> = now() to onMainThread()

    /** Times a synchronous [body]; rethrows its error after recording it. */
    inline fun <T> measure(
        system: String, operation: String, collection: String? = null, namespace: String? = null, body: () -> T,
    ): T {
        val (t0, main) = start()
        val r = try {
            body()
        } catch (e: Throwable) {
            report(system, operation, collection, namespace, t0, main, e)
            throw e
        }
        report(system, operation, collection, namespace, t0, main)
        return r
    }

    /**
     * Times a suspending [body] (DataStore `updateData`, Room suspend DAOs). Its duration includes
     * suspension, so it is never judged as main-thread work.
     */
    suspend fun <T> measureSuspend(
        system: String, operation: String, collection: String? = null, namespace: String? = null, body: suspend () -> T,
    ): T {
        val t0 = now()
        val r = try {
            body()
        } catch (e: Throwable) {
            report(system, operation, collection, namespace, t0, false, e)
            throw e
        }
        report(system, operation, collection, namespace, t0, false)
        return r
    }

    private val OP = Regex("""^\s*(?:with\b[\s\S]*?\)\s*)?([a-z]+)""", RegexOption.IGNORE_CASE)
    private val TARGET = Regex(
        """\b(?:from|into|update|join|table)\s+((?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[\w$]+)(?:\.(?:"[^"]+"|[\w$]+))?)""",
        RegexOption.IGNORE_CASE,
    )

    /** `SELECT users`-style parts (same rules as the other SDKs): never values or the statement text. */
    @JvmStatic
    fun querySummary(sql: String?): Pair<String, String?> {
        val s = sql ?: ""
        val op = OP.find(s)?.groupValues?.get(1)?.uppercase() ?: "QUERY"
        return op to TARGET.find(s)?.groupValues?.get(1)?.filterNot { it in "\"`[]" }
    }

    /**
     * Reports [file]'s size and, when present, its `-wal` file as `storage` events
     * (`wt.embedded.file_size`, `wt.embedded.wal_size`). A disk stat: call it off the main thread.
     * Room: `context.getDatabasePath("app.db")`; MMKV: `File(MMKV.getRootDir(), id)`.
     */
    @JvmStatic
    @JvmOverloads
    fun recordStoreSize(system: String, file: File, namespace: String = file.name) {
        val at = mapOf("db.system.name" to system, "db.namespace" to namespace)
        if (file.exists()) sink(Event("storage", "wt.embedded.file_size", value = file.length().toDouble(), attrs = at))
        val wal = File(file.path + "-wal")
        if (wal.exists()) sink(Event("storage", "wt.embedded.wal_size", value = wal.length().toDouble(), attrs = at))
    }
}
