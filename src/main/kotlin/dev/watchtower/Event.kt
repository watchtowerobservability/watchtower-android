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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ts_ns", tsNs)
        put("type", type)
        put("name", name)
        value?.let { put("value", it) }
        put("attrs", JSONObject(safeAttrs(attrs)))
    }
}

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
        .mapValues { (_, v) -> if (v is String && v.length > 2048) v.take(2048) else v }

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
