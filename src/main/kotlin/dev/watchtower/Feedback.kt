package dev.watchtower

/**
 * The `POST /v1/rum/feedback` body (ERR-014), like the browser SDK's `feedbackBody`: the message
 * trimmed and cut to 4096 characters, name and email to 256, and `event_id` only when it is 32 hex
 * characters once the dashes of an event UUID are gone. Null without a message.
 */
fun feedbackBody(message: String, name: String?, email: String?, eventId: String?): Map<String, String>? {
    val m = message.trim().take(4096)
    if (m.isEmpty()) return null
    val b = linkedMapOf("message" to m)
    val id = eventId.orEmpty().replace("-", "").lowercase()
    if (id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }) b["event_id"] = id
    name?.trim()?.take(256)?.takeIf { it.isNotEmpty() }?.let { b["name"] = it }
    email?.trim()?.take(256)?.takeIf { it.isNotEmpty() }?.let { b["email"] = it }
    return b
}
