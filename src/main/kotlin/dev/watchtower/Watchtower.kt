package dev.watchtower

import android.app.Application
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Entry point: `Watchtower.init(app, Config(key = "...", service = "shop"))` in `Application.onCreate`. */
object Watchtower {
    private var cfg: Config? = null
    internal val config: Config? get() = cfg
    private var buffer: OfflineBuffer? = null
    private var transport: Transport? = null
    val sessionId: String = UUID.randomUUID().toString()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wt-io").apply { isDaemon = true } }
    @Volatile private var lastErrorId: String? = null
    @Volatile internal var startedMs = 0L

    /** Consecutive launches before this one that crashed within 5 s of start; 0 again once this run passes 5 s. */
    @Volatile var launchCrashCount = 0
        internal set

    /**
     * The app keeps crashing at launch (`launchCrashCount >= Config.crashLoopThreshold`): a cue for a
     * safe mode, such as skipping remote config or clearing caches. Read it after [init].
     */
    val isCrashLooping: Boolean get() = launchCrashCount >= (cfg?.crashLoopThreshold ?: 2)

    @Synchronized
    fun init(app: Application, config: Config) {
        if (cfg != null) return
        cfg = config
        val device = mapOf<String, Any>(
            "ua" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "os.name" to "Android",
            "os.version" to Build.VERSION.RELEASE,
            "device.model" to Build.MODEL,
        )
        val buf = OfflineBuffer(File(app.filesDir, "wt-offline"), config.offlineMaxBytes)
        buffer = buf
        transport = Transport(config, buf, sessionId, device, app.packageName)
        val prev = Run.start(app.filesDir, sessionId)
        startedMs = System.currentTimeMillis()
        launchCrashCount = CrashLoop.count(prev, prev?.crashed == true)
        Run.update { it.copy(launchCrashes = launchCrashCount) }
        io.schedule(Runnable {
            launchCrashCount = 0 // this run survived its launch
            Run.update { it.copy(launchCrashes = 0) }
        }, CrashLoop.WINDOW_MS, TimeUnit.MILLISECONDS)
        Instrument.install(app, config)
        Synthetic.install(app)
        // before the first flush, so the previous session's end goes out with what it left on disk
        if (prev != null) io.execute { runCatching { Run.endPrevious(app, prev) } }
        if (config.replay.enabled) Replay.install(app, config, this)
        io.scheduleWithFixedDelay({ Run.update { it }; transport?.flush() }, 5, 10, TimeUnit.SECONDS)
        io.execute { transport?.flush() } // sends what a crash left on disk last run
    }

    /** Symbolication and replay links every error carries (an event's own attrs win). */
    internal fun errorLinks(c: Config, session: String): Map<String, Any> = buildMap {
        c.buildId?.let { put("debug.build_id", it) }
        if (c.replay.enabled) put("replay.session_id", session) // masked replay as error attachment (M16)
    }

    /** Buffer one event after the redaction hook; durable on return (written before any crash). */
    fun record(e: Event) = record(e, sessionId)

    /** [session] is the run the event belongs to (an earlier one for what the next launch reports). */
    internal fun record(e: Event, session: String) {
        val c = cfg ?: return
        val tagged = if (e.type == "error") e.copy(attrs = errorLinks(c, session) + e.attrs) else e
        val out = runCatching { c.redact.redact(tagged) }.getOrNull() ?: return
        buffer?.add(Synthetic.tag(out.toJson()).put(SESSION_KEY, session).toString())
        if (out.type == "error") lastErrorId = out.id
    }

    /**
     * User feedback (ERR-014) about [eventId], by default the last error recorded. Sent on the SDK's
     * I/O thread; [done] (called there) gets true when the edge accepted it. Never throws.
     */
    @JvmOverloads
    fun feedback(
        message: String, name: String? = null, email: String? = null, eventId: String? = null,
        done: ((Boolean) -> Unit)? = null,
    ) {
        val body = feedbackBody(message, name, email, eventId ?: lastErrorId)
        if (body == null || transport == null) { done?.invoke(false); return }
        io.execute {
            val json = JSONObject(body).toString().toByteArray()
            val code = transport?.post("/v1/rum/feedback", json, System.currentTimeMillis(), gzip = false)
            done?.invoke(code != null && code < 300)
        }
    }

    fun recordError(t: Throwable, handled: Boolean = true) = record(
        Event(
            "error", t.javaClass.simpleName,
            attrs = mapOf(
                "error.message" to (t.message ?: ""),
                "error.stack" to t.stackTraceToString().take(8000),
                "crash.handled" to handled,
            ),
        ),
    )

    /** Uploads one replay chunk (the JSON array of events) for this session. */
    internal fun postReplay(seq: Int, json: String) {
        val c = cfg ?: return
        io.execute {
            transport?.post(
                "/v1/replay/$sessionId/chunks?seq=$seq", json.toByteArray(), System.currentTimeMillis(),
                mapOf("X-Wt-Mask" to if (c.replay.maskText) "strict" else "balanced"),
            )
        }
    }

    fun flush() { io.execute { transport?.flush() } }
}
