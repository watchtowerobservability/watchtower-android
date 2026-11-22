package dev.watchtower

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp network capture (RUM-010): `OkHttpClient.Builder().addInterceptor(WatchtowerInterceptor())`.
 * Every call becomes a RUM `resource` event (URL without query, method, status, duration, size,
 * span id); calls to a [Config.propagateTo] prefix get a W3C `traceparent` header first. A
 * `traceparent` the app already set is kept and its ids are used. OkHttp is a compileOnly
 * dependency: apps without it never load this class.
 */
class WatchtowerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val cfg = Watchtower.config
        val url = req.url.toString()
        if (cfg == null || Network.isOwn(url, cfg)) return chain.proceed(req)
        val existing = req.header("traceparent")?.let(Network::parseTraceparent)
        val ids = existing ?: if (Network.shouldPropagate(url, cfg)) Network.hex(16) to Network.hex(8) else null
        val out = if (existing == null && ids != null) {
            req.newBuilder().header("traceparent", Network.traceparent(ids.first, ids.second)).build()
        } else {
            req
        }
        val start = System.currentTimeMillis()
        val t0 = System.nanoTime()
        val res = try {
            chain.proceed(out)
        } catch (e: IOException) {
            record(url, req.method, null, t0, null, start, ids)
            throw e
        }
        val size = res.body?.contentLength()?.takeIf { it >= 0 } ?: res.header("Content-Length")?.toLongOrNull()
        record(url, req.method, res.code, t0, size, start, ids)
        return res
    }

    @Suppress("LongParameterList")
    private fun record(url: String, method: String, status: Int?, t0: Long, size: Long?, start: Long, ids: Pair<String, String>?) =
        runCatching {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            Watchtower.record(Network.resourceEvent(url, method, status, ms, size, start, ids))
        }
}
