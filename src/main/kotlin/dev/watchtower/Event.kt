package dev.watchtower

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One RUM event in the `RumEventV1` wire shape. */
data class Event(
    val type: String,
    val name: String,
    val value: Double? = null,
    val attrs: Map<String, Any> = emptyMap(),
    val tsNs: Long = System.currentTimeMillis() * 1_000_000L,
    val id: String = UUID.randomUUID().toString(),
    /** 32 lowercase hex chars, for a `resource` event that carried a `traceparent`. */
    val traceId: String? = null,
    /** 16 lowercase hex chars. */
    val spanId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ts_ns", tsNs)
        put("type", type)
        put("name", name)
        value?.let { put("value", it) }
        traceId?.let { put("trace_id", it) }
        spanId?.let { put("span_id", it) }
        put("attrs", JSONObject(safeAttrs(attrs)))
    }
}

/** Buffer-only field: the session an event belongs to. [Transport] strips it before sending. */
internal const val SESSION_KEY = "_sid"

/** Receives every event before it is buffered; return null to drop it. */
fun interface Redactor {
    fun redact(e: Event): Event?
}

/**
 * Attributes the app supplies must not use the `wt.` namespace: the gateway deletes incoming `wt.*`
 * keys, so they would only be lost. They are removed here, and values are cut to the wire limit.
 */
fun safeAttrs(a: Map<String, Any>): Map<String, Any> =
    a.filterKeys { !it.startsWith("wt.") && it.length <= 128 }
        .mapValues { (k, v) ->
            val cap = if (k in CRASH_ATTR_KEYS) 32_768 else 2048 // the edge's limits for crash attributes
            if (v is String && v.length > cap) v.take(cap) else v
        }

/** Attributes the edge lets run to 32 KiB (stacks cut mid-JSON would be unusable). */
private val CRASH_ATTR_KEYS = setOf("error.stack", "stack", "frames", "threads", "error.threads", "debug_images")

fun batchJson(cfg: Config, session: String, device: Map<String, Any>, events: List<JSONObject>): String =
    JSONObject().apply {
        put("session_id", session)
        put("sent_at_ns", System.currentTimeMillis() * 1_000_000L)
        put("sdk", JSONObject().put("name", SDK_NAME).put("version", SDK_VERSION))
        put(
            "app",
            JSONObject().put("service", cfg.service).apply {
                cfg.release?.let { put("release", it) }
                cfg.environment?.let { put("env", it) }
            },
        )
        put("page", JSONObject().put("url", "app://${cfg.service}"))
        put("device", JSONObject().put("ua", device["ua"] ?: "android"))
        put("attrs", JSONObject(safeAttrs(device)))
        put("events", JSONArray(events))
    }.toString()
