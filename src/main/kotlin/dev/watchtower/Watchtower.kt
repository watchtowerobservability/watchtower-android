package dev.watchtower

import android.app.Application
import android.os.Build
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Entry point: `Watchtower.init(app, Config(key = "...", service = "shop"))` in `Application.onCreate`. */
object Watchtower {
    private var cfg: Config? = null
    private var buffer: OfflineBuffer? = null
    private var transport: Transport? = null
    val sessionId: String = UUID.randomUUID().toString()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wt-io").apply { isDaemon = true } }

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
        transport = Transport(config, buf, sessionId, device)
        Instrument.install(app, config)
        if (config.replay.enabled) Replay.install(app, config, this)
        io.scheduleWithFixedDelay({ transport?.flush() }, 5, 10, TimeUnit.SECONDS)
        io.execute { transport?.flush() } // sends what a crash left on disk last run
    }

    /** Buffer one event after the redaction hook; durable on return (written before any crash). */
    fun record(e: Event) {
        val c = cfg ?: return
        val out = runCatching { c.redact.redact(e) }.getOrNull() ?: return
        buffer?.add(out.toJson().toString())
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
