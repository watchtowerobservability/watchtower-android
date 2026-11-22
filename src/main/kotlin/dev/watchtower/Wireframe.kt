package dev.watchtower

import org.json.JSONArray
import org.json.JSONObject

/**
 * One view of a wireframe: box, colour and (already masked) text. No pixels, so a frame is a few
 * KB, and nothing leaves the device that the masking rules did not clear.
 */
data class WireNode(
    val id: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** `view`, `text`, `input` or `image` (a placeholder box). */
    val kind: String,
    /** `#rrggbb`. */
    val bg: String? = null,
    val text: String? = null,
)

/** The rule [maskRects] applies to pixels, applied to text: `maskText` hides text and inputs; passwords and `wt-mask` always; `wt-unmask` lifts a text view only. */
fun hidesText(kind: Kind, maskText: Boolean, unmask: Boolean = false, forceMask: Boolean = false): Boolean =
    forceMask || kind == Kind.PASSWORD ||
        (kind == Kind.INPUT && (maskText || !unmask)) ||
        (kind == Kind.TEXT && maskText && !unmask)

/** Text as it may leave the device: cut to 200 chars; hidden text becomes `*` of the same length (at most 64). */
fun wireText(s: String, hide: Boolean): String {
    val t = s.take(200)
    return if (hide) "*".repeat(minOf(t.length, 64)) else t
}

fun wireKind(k: Kind): String = when (k) {
    Kind.TEXT -> "text"
    Kind.INPUT, Kind.PASSWORD -> "input"
    Kind.IMAGE -> "image"
    Kind.OTHER -> "view"
}

fun hexColor(argb: Int): String? =
    if ((argb ushr 24) < 3) null else String.format("#%02x%02x%02x", (argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff)

/**
 * Turns successive node lists into `wt.wireframe` rrweb custom events: a full snapshot first and
 * every [keyEvery] frames, otherwise only the changed nodes plus the paint order. A frame with no
 * change is not sent (null).
 */
class WireframeEncoder(var keyEvery: Int = 10) {
    private var prev: Map<Int, WireNode> = emptyMap()
    private var prevOrder: List<Int> = emptyList()
    private var count = 0

    fun encode(nodes: List<WireNode>, w: Int, h: Int, screen: String, tsMs: Long): JSONObject? {
        val full = count % maxOf(1, keyEvery) == 0
        val order = nodes.map { it.id }
        val changed = if (full) nodes else nodes.filter { prev[it.id] != it }
        if (!full && changed.isEmpty() && order == prevOrder) return null
        count++
        prev = nodes.associateBy { it.id }
        prevOrder = order
        val payload = JSONObject().put("masked", true).put("full", full).put("w", w).put("h", h).put("screen", screen)
            .put("order", JSONArray(order)).put("nodes", JSONArray(changed.map(::json)))
        return JSONObject().put("type", 5).put("timestamp", tsMs)
            .put("data", JSONObject().put("tag", "wt.wireframe").put("payload", payload))
    }

    companion object {
        fun json(n: WireNode): JSONObject = JSONObject().put("id", n.id).put("x", n.x).put("y", n.y).put("w", n.w).put("h", n.h)
            .put("k", n.kind).apply {
                n.bg?.let { put("bg", it) }
                n.text?.let { put("t", it) }
            }
    }
}
