package dev.watchtower

/**
 * App launch timing (RUM-010): one `app_launch_cold` or `app_launch_warm` vital per start, value =
 * total milliseconds to the first drawn frame, with the phase breakdown as attributes. Pure: the
 * lifecycle hooks in [Instrument] feed it `elapsedRealtime` marks, so it is unit tested on the JVM.
 *
 * Cold phases: process (fork to SDK start, includes content providers), application (SDK start
 * to first activity created), activity (created to resumed), first_frame (resumed to first draw).
 * Warm: the app comes back from the background; activity (created or started to resumed) and
 * first_frame. A process started in the background (a broadcast, a job) that shows an activity
 * later than [BACKGROUND_START_MS] is not a cold launch and is reported as warm.
 */
class LaunchTracker(private val processStartMs: Long, private val sdkStartMs: Long) {
    private var coldDone = false
    private var visible = 0
    private var phaseStart = -1L
    private var created = false
    private var resumedAt = -1L
    private var screen = ""
    private var pending = false
    private var rotating = false

    companion object {
        const val BACKGROUND_START_MS = 10_000L
    }

    fun activityCreated(nowMs: Long) {
        if (visible == 0 && !pending && !rotating) begin(nowMs, created = true)
    }

    fun activityStarted(nowMs: Long) {
        if (visible == 0 && !pending && !rotating) begin(nowMs, created = false)
        rotating = false
        visible++
    }

    /** [changingConfig]: a rotation or similar recreates the activity; that is not a launch. */
    fun activityStopped(changingConfig: Boolean = false) {
        if (visible > 0) visible--
        if (changingConfig) rotating = true
    }

    private fun begin(nowMs: Long, created: Boolean) {
        pending = true
        phaseStart = nowMs
        this.created = created
        resumedAt = -1L
    }

    fun activityResumed(nowMs: Long, name: String) {
        if (pending && resumedAt < 0) {
            resumedAt = nowMs
            screen = name
        }
    }

    /** The first frame after a resume was drawn; returns the launch event, if this was a launch. */
    fun firstFrame(nowMs: Long): Event? {
        if (!pending || resumedAt < 0) return null
        pending = false
        val activity = (resumedAt - phaseStart).coerceAtLeast(0)
        val frame = (nowMs - resumedAt).coerceAtLeast(0)
        val cold = !coldDone && created && phaseStart - sdkStartMs <= BACKGROUND_START_MS
        val wasFirst = !coldDone
        coldDone = true
        if (cold) {
            val process = (sdkStartMs - processStartMs).coerceAtLeast(0)
            val application = (phaseStart - sdkStartMs).coerceAtLeast(0)
            return Event(
                "vital", "app_launch_cold", (nowMs - processStartMs).coerceAtLeast(0).toDouble(),
                mapOf(
                    "screen" to screen, "launch.type" to "cold",
                    "launch.phase.process_ms" to process, "launch.phase.application_ms" to application,
                    "launch.phase.activity_ms" to activity, "launch.phase.first_frame_ms" to frame,
                ),
            )
        }
        return Event(
            "vital", "app_launch_warm", (activity + frame).toDouble(),
            mapOf(
                "screen" to screen, "launch.type" to "warm", "launch.activity_created" to created,
                "launch.background_process" to wasFirst,
                "launch.phase.activity_ms" to activity, "launch.phase.first_frame_ms" to frame,
            ),
        )
    }
}
