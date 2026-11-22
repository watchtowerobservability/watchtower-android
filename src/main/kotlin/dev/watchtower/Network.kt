package dev.watchtower

import java.security.SecureRandom

/** Network capture helpers shared by [WatchtowerInterceptor]. Pure, so they run in JVM tests. */
internal object Network {
    private val rnd = SecureRandom()

    /** Query and fragment dropped: both can carry tokens. */
    fun scrubUrl(u: String): String {
        val i = u.indexOfFirst { it == '?' || it == '#' }
        return if (i < 0) u else u.substring(0, i)
    }

    /** The edge itself is never captured and never gets a trace header. */
    fun isOwn(url: String, cfg: Config): Boolean = url.startsWith(cfg.endpoint.trimEnd('/'))

    /** Prefix match against [Config.propagateTo]; nothing is implicit. */
    fun shouldPropagate(url: String, cfg: Config): Boolean =
        url.isNotEmpty() && !isOwn(url, cfg) && cfg.propagateTo.any { it.isNotEmpty() && url.startsWith(it) }

    fun hex(bytes: Int): String {
        val b = ByteArray(bytes).also(rnd::nextBytes)
        if (b.all { it == 0.toByte() }) b[bytes - 1] = 1 // all-zero ids are invalid in W3C
        return b.joinToString("") { "%02x".format(it) }
    }

    fun traceparent(traceId: String, spanId: String): String = "00-$traceId-$spanId-01"

    /** (trace id, span id) from an existing `traceparent`, or null when it is malformed. */
    fun parseTraceparent(h: String): Pair<String, String>? {
        val p = h.trim().split('-')
        val ok = p.size >= 4 && p[1].length == 32 && p[2].length == 16 &&
            (p[1] + p[2]).all { it in '0'..'9' || it in 'a'..'f' }
        return if (ok) p[1] to p[2] else null
    }

    /** One RUM `resource` event (browser shape: name = initiator type, value = duration ms). */
    fun resourceEvent(
        url: String,
        method: String,
        status: Int?,
        durationMs: Long,
        bytes: Long?,
        startMs: Long,
        ids: Pair<String, String>?,
    ): Event {
        val attrs = mutableMapOf<String, Any>(
            "url" to scrubUrl(url),
            "method" to method.uppercase(),
            "initiator_type" to "native",
        )
        status?.let { attrs["status"] = it }
        bytes?.takeIf { it >= 0 }?.let { attrs["transfer_size"] = it }
        if (status == null || status >= 400) attrs["failed"] = true
        return Event(
            "resource", "native", durationMs.coerceAtLeast(0).toDouble(), attrs,
            tsNs = startMs * 1_000_000L, traceId = ids?.first, spanId = ids?.second,
        )
    }
}
