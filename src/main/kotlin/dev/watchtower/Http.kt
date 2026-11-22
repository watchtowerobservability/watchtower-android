package dev.watchtower

import java.io.IOException
import java.net.HttpURLConnection

/**
 * HttpURLConnection capture (RUM-010), for code that does not use OkHttp:
 * `val body = WatchtowerHttp.trace(url.openConnection() as HttpURLConnection) { it.inputStream.readBytes() }`.
 * Adds `traceparent` for [Config.propagateTo] prefixes before [block] connects, then records one
 * `resource` event (status, duration, size). Failures are recorded and rethrown unchanged.
 */
object WatchtowerHttp {
    fun <T> trace(conn: HttpURLConnection, block: (HttpURLConnection) -> T): T {
        val cfg = Watchtower.config ?: return block(conn)
        val url = conn.url.toString()
        if (Network.isOwn(url, cfg)) return block(conn)
        val existing = conn.getRequestProperty("traceparent")?.let(Network::parseTraceparent)
        val ids = existing ?: if (Network.shouldPropagate(url, cfg)) Network.hex(16) to Network.hex(8) else null
        if (existing == null && ids != null) conn.setRequestProperty("traceparent", Network.traceparent(ids.first, ids.second))
        val start = System.currentTimeMillis()
        val t0 = System.nanoTime()
        try {
            val out = block(conn)
            record(conn, url, t0, start, ids)
            return out
        } catch (e: IOException) {
            record(conn, url, t0, start, ids, failed = true)
            throw e
        }
    }

    private fun record(conn: HttpURLConnection, url: String, t0: Long, start: Long, ids: Pair<String, String>?, failed: Boolean = false) =
        runCatching {
            val status = if (failed) null else conn.responseCode.takeIf { it > 0 }
            val size = conn.contentLengthLong.takeIf { it >= 0 }
            val ms = (System.nanoTime() - t0) / 1_000_000L
            Watchtower.record(Network.resourceEvent(url, conn.requestMethod, status, ms, size, start, ids))
        }
}
