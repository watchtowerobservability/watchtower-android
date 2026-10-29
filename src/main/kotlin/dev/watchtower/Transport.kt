package dev.watchtower

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** Sends buffered events to the edge. Never throws; a failed send leaves the events on disk. */
class Transport(
    private val cfg: Config,
    private val buffer: OfflineBuffer,
    private val session: String,
    private val device: Map<String, Any>,
) {
    @Volatile private var retryAtMs = 0L
    private var failures = 0

    fun flush(now: Long = System.currentTimeMillis()) {
        if (now < retryAtMs) return
        val batch = buffer.peek(200)
        if (batch.isEmpty()) return
        val json = batchJson(cfg, session, device, batch.map { JSONObject(it.second) })
        val status = post("/v1/rum/events", json.toByteArray(), now) ?: return
        when {
            status < 300 -> { failures = 0; buffer.remove(batch.map { it.first }) }
            status == 429 || status >= 500 -> backoff(now)
            // other 4xx: the batch is invalid and retrying cannot help
            else -> buffer.remove(batch.map { it.first })
        }
    }

    /** POST one gzip body; null when the network is down (and the backoff window is armed). */
    fun post(path: String, body: ByteArray, now: Long, extra: Map<String, String> = emptyMap()): Int? = try {
        val c = URL(cfg.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Content-Encoding", "gzip")
        extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
        GZIPOutputStream(c.outputStream).use { it.write(body) }
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
