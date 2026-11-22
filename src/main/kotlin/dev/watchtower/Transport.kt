package dev.watchtower

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

const val APP_ID_HEADER = "X-Watchtower-App-Id"

/** Sends buffered events to the edge. Never throws; a failed send leaves the events on disk. */
class Transport(
    private val cfg: Config,
    private val buffer: OfflineBuffer,
    private val session: String,
    private val device: Map<String, Any>,
    /** The package name: a native app has no Origin, so the edge checks it against the key's app ids. */
    private val appId: String? = null,
) {
    @Volatile private var retryAtMs = 0L
    private var failures = 0

    fun flush(now: Long = System.currentTimeMillis()) {
        if (now < retryAtMs) return
        val batch = buffer.peek(200)
        if (batch.isEmpty()) return
        val parsed = batch.map { (f, text) -> f to runCatching { JSONObject(text) }.getOrNull() }
        buffer.remove(parsed.filter { it.second == null }.map { it.first }) // unreadable: never sendable
        // one batch per session: events a previous run left on disk keep that run's session
        val groups = parsed.mapNotNull { (f, o) -> o?.let { f to it } }
            .groupBy { (_, o) -> (o.remove(SESSION_KEY) as? String)?.takeIf { it.isNotEmpty() } ?: session }
        for ((sid, items) in groups) {
            val json = batchJson(cfg, sid, device, items.map { it.second })
            val status = post("/v1/rum/events", json.toByteArray(), now) ?: return
            when {
                status < 300 -> { failures = 0; buffer.remove(items.map { it.first }) }
                status == 429 || status >= 500 -> { backoff(now); return }
                // other 4xx: the batch is invalid and retrying cannot help
                else -> buffer.remove(items.map { it.first })
            }
        }
    }

    /** Headers of every POST. */
    fun headers(gzip: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${cfg.key}")
        put("Content-Type", "application/json")
        if (gzip) put("Content-Encoding", "gzip")
        appId?.let { put(APP_ID_HEADER, it) }
    }

    /** POST one body (gzip unless [gzip] is false); null when the network is down (and the backoff window is armed). */
    fun post(path: String, body: ByteArray, now: Long, extra: Map<String, String> = emptyMap(), gzip: Boolean = true): Int? = try {
        val c = URL(cfg.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        (headers(gzip) + extra).forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (gzip) GZIPOutputStream(c.outputStream).use { it.write(body) } else c.outputStream.use { it.write(body) }
        val code = c.responseCode
        c.disconnect()
        code
    } catch (_: Exception) {
        backoff(now)
        null
    }

    private fun backoff(now: Long) {
        failures++
        retryAtMs = now + minOf(300_000L, 1000L shl minOf(failures, 8))
    }
}
