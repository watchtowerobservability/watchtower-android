package dev.watchtower

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** What the SDK knows about one process run, kept on disk so the next launch can end its session. */
data class RunState(
    val session: String,
    val pid: Int,
    val startMs: Long,
    val lastMs: Long,
    val foreground: Boolean = false,
    /** The uncaught-exception handler recorded a crash. */
    val crashed: Boolean = false,
    /** The ANR watchdog saw the main thread hung (and already reported it). */
    val hanging: Boolean = false,
    /** Consecutive launch crashes before this run ([CrashLoop.count]). */
    val launchCrashes: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("session", session).put("pid", pid).put("start_ms", startMs).put("last_ms", lastMs)
        .put("fg", foreground).put("crashed", crashed).put("hanging", hanging)
        .put("launch_crashes", launchCrashes).toString()

    companion object {
        fun parse(s: String): RunState? = runCatching {
            val o = JSONObject(s)
            RunState(
                o.getString("session"), o.getInt("pid"), o.getLong("start_ms"), o.getLong("last_ms"),
                o.optBoolean("fg"), o.optBoolean("crashed"), o.optBoolean("hanging"), o.optInt("launch_crashes"),
            )
        }.getOrNull()
    }
}

/**
 * How the previous session ended (RUM-009), sent as `session.end_reason` on a `session_end` event
 * of that session: crash, anr, oom, native_crash, user_exit or background_timeout. On API 30+ the
 * reason comes from `ApplicationExitInfo`; before that only crashes are known and the rest is
 * user_exit (foreground) or background_timeout (killed while in the background).
 */
object SessionEnd {
    fun reason(exitReason: Int?, prev: RunState): String = when (exitReason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "oom"
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_EXIT_SELF,
        -> if (prev.crashed) "crash" else "user_exit"
        else -> when {
            prev.crashed -> "crash"
            prev.foreground && prev.hanging -> "anr"
            prev.foreground -> "user_exit"
            else -> "background_timeout"
        }
    }

    fun endEvent(reason: String, prev: RunState): Event = Event(
        "action", "session_end",
        attrs = mapOf("session.end_reason" to reason, "app.in_foreground" to prev.foreground),
        tsNs = prev.lastMs * 1_000_000L,
    )

    /**
     * The error event for a failure only the exit record shows; null when the SDK reported it
     * live (Java crash, an ANR the watchdog saw) or it is no failure. A low-memory kill counts as
     * an OOM error only in the foreground: background kills are the system reclaiming memory.
     */
    fun errorFor(reason: String, prev: RunState, description: String?, detail: String?): Event? {
        val base = mapOf<String, Any>(
            "error.message" to (description ?: ""), "crash.handled" to false,
            "crash.previous_session" to true, "app.in_foreground" to prev.foreground,
        )
        val ts = prev.lastMs * 1_000_000L
        return when {
            reason == "native_crash" -> Event(
                "error", "NativeCrash",
                attrs = base + ("crash.class" to "native") + detail.attr("crash.tombstone_b64"), tsNs = ts,
            )
            reason == "anr" && !prev.hanging -> {
                // the ART thread dump: the main thread's stack, then the other threads
                val t = detail?.let { AnrTrace.parse(it) }
                val stacks = if (t != null) mapOf("error.stack" to t.mainStack, "error.threads" to t.threads) else detail?.take(8000).attr("error.stack")
                Event("error", "ApplicationNotResponding", attrs = base + ("crash.class" to "anr") + stacks, tsNs = ts)
            }
            reason == "oom" && prev.foreground -> Event(
                "error", "OutOfMemory", attrs = base + ("crash.class" to "oom"), tsNs = ts,
            )
            else -> null
        }
    }

    private fun String?.attr(key: String): Map<String, Any> = if (this == null) emptyMap() else mapOf(key to this)
}

/** This run's [RunState] file; the hooks in [Instrument] keep it current. */
internal object Run {
    private var file: File? = null
    private var state: RunState? = null

    /** Starts this run and returns the previous one, if it left a state behind. */
    @Synchronized
    fun start(dir: File, session: String): RunState? {
        val f = File(dir, "wt-run.json")
        file = f
        val prev = runCatching { RunState.parse(f.readText()) }.getOrNull()
        val now = System.currentTimeMillis()
        state = RunState(session, Process.myPid(), now, now)
        write()
        NativeCrash.install(dir)
        return prev
    }

    @Synchronized
    fun update(fn: (RunState) -> RunState) {
        state = state?.let(fn)?.copy(lastMs = System.currentTimeMillis())
        write()
    }

    private fun write() {
        val f = file ?: return
        val s = state ?: return
        runCatching { f.writeText(s.toJson()) }
    }

    /** Records the previous session's failure (if any) and its end, under that session's id. */
    fun endPrevious(app: Application, prev: RunState) {
        var reason: Int? = null
        var description: String? = null
        var extra: String? = null
        var decoded: Tombstone.Decoded? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exitOf(app, prev)?.let { e ->
                reason = e.reason
                description = e.description
                extra = runCatching { detail(e) }.getOrNull()
                decoded = runCatching { tombstone(e) }.getOrNull()
            }
        }
        var r = SessionEnd.reason(reason, prev)
        var err = SessionEnd.errorFor(r, prev, description, extra)
        // SDK-003: the NDK handler's report adds frames (and is the only record below API 30)
        NativeCrash.take(app.filesDir)?.let { NativeCrash.event(it, prev) }?.let { native ->
            val tomb = err?.attrs?.get("crash.tombstone_b64")
            r = "native_crash"
            err = if (tomb == null) native else native.copy(attrs = native.attrs + ("crash.tombstone_b64" to tomb))
        }
        // the decoded tombstone replaces the raw prefix: the full backtrace with build ids, which
        // the server symbolicates against the uploaded symbol files
        decoded?.let { d -> err = err?.let { it.copy(attrs = it.attrs - "crash.tombstone_b64" + Tombstone.attrs(d)) } }
        // a native crash is only known from the exit record: count and tag it here, not at init
        val age = if (r == "native_crash" && !prev.crashed) CrashLoop.launchCrashAge(prev, true) else null
        if (age != null) {
            val n = prev.launchCrashes + 1
            Watchtower.launchCrashCount = n
            update { it.copy(launchCrashes = n) }
            err = err?.let { it.copy(attrs = it.attrs + CrashLoop.attrs(age, n)) }
        }
        err?.let { Watchtower.record(it, prev.session) }
        Watchtower.record(SessionEnd.endEvent(r, prev), prev.session)
    }

    /** The exit record of the previous process; the newest timestamp seen is remembered. */
    @TargetApi(Build.VERSION_CODES.R)
    private fun exitOf(app: Application, prev: RunState): ApplicationExitInfo? {
        val mark = File(app.filesDir, "wt-exit.ts")
        val seen = runCatching { mark.readText().trim().toLong() }.getOrDefault(0L)
        val am = app.getSystemService(ActivityManager::class.java) ?: return null
        val all = runCatching { am.getHistoricalProcessExitReasons(null, 0, 16) }.getOrNull().orEmpty()
        all.maxOfOrNull { it.timestamp }?.let { ts -> runCatching { mark.writeText(ts.toString()) } }
        return all.firstOrNull { it.timestamp > seen && it.pid == prev.pid && it.timestamp >= prev.startMs }
    }

    /** ANR: the text trace. Native crash (API 31+): the start of the protobuf tombstone, base64. */
    @TargetApi(Build.VERSION_CODES.R)
    private fun detail(e: ApplicationExitInfo): String? = when (e.reason) {
        ApplicationExitInfo.REASON_ANR -> e.traceInputStream?.use { String(readUpTo(it, MAX_ANR_TRACE)) }
        ApplicationExitInfo.REASON_CRASH_NATIVE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // fallback when [tombstone] cannot decode it: the first 1.5 KB, raw
            e.traceInputStream?.use { Base64.encodeToString(readUpTo(it, 1536), Base64.NO_WRAP) }
        } else {
            null
        }
        else -> null
    }

    /** The decoded protobuf tombstone of a native crash (API 31+), else null. */
    @TargetApi(Build.VERSION_CODES.S)
    private fun tombstone(e: ApplicationExitInfo): Tombstone.Decoded? {
        if (e.reason != ApplicationExitInfo.REASON_CRASH_NATIVE || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return e.traceInputStream?.use { Tombstone.decode(readUpTo(it, MAX_TOMBSTONE)) }
    }

    private const val MAX_TOMBSTONE = 256 * 1024
    private const val MAX_ANR_TRACE = 256 * 1024

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }
}
