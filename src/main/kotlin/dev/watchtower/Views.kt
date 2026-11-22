package dev.watchtower

/**
 * Automatic screen views (RUM-010), in the browser SDK's `view` shape: name and `route` = the
 * screen, `url` = `app://<service>/<screen>`, and `previous_route` / `previous_duration_ms` (time
 * spent on the screen before). Pure: [Instrument] feeds it activity resume/pause marks.
 *
 * Fragments are not tracked: androidx.fragment is not a dependency of this SDK. Apps with
 * fragment-based navigation record their screens with `Watchtower.record(Event("view", name))`.
 */
class ViewTracker(private val service: String) {
    /** The screen shown now; frame vitals carry it. */
    var current: String? = null
        private set
    private var since = 0L
    private var previous: String? = null
    private var previousMs: Long? = null

    fun appeared(name: String, nowMs: Long): Event? {
        if (name == current) return null
        current?.let { c ->
            previous = c
            previousMs = (nowMs - since).coerceAtLeast(0)
        }
        current = name
        since = nowMs
        val attrs = mutableMapOf<String, Any>("route" to name, "url" to "app://$service/$name")
        previous?.let { attrs["previous_route"] = it }
        previousMs?.let { attrs["previous_duration_ms"] = it }
        return Event("view", name, attrs = attrs, tsNs = nowMs * 1_000_000L)
    }

    fun disappeared(name: String, nowMs: Long) {
        if (name != current) return
        previous = name
        previousMs = (nowMs - since).coerceAtLeast(0)
        current = null
    }
}
