package dev.watchtower

/**
 * Crash-loop guard (DX 15). A crash within [WINDOW_MS] of the SDK start is a launch crash;
 * [RunState.launchCrashes] counts consecutive launches that ended in one, so the app can enter a
 * safe mode ([Watchtower.isCrashLooping]). Pure, so it is unit tested.
 */
object CrashLoop {
    const val WINDOW_MS = 5_000L

    /**
     * Milliseconds from launch to the previous run's crash, when it crashed inside the window. A
     * native crash has no time of its own: `lastMs` is the last sign of life, and a run that passed
     * the window always wrote one after it (the reset at [WINDOW_MS]).
     */
    fun launchCrashAge(prev: RunState, crashed: Boolean): Long? =
        (prev.lastMs - prev.startMs).coerceAtLeast(0L).takeIf { crashed && it < WINDOW_MS }

    /** This run's count: the previous run's plus one after a launch crash, else 0. */
    fun count(prev: RunState?, crashed: Boolean): Int =
        if (prev != null && launchCrashAge(prev, crashed) != null) prev.launchCrashes + 1 else 0

    /** The launch tags of a crash [sinceLaunchMs] after start; empty outside the window. */
    fun attrs(sinceLaunchMs: Long, loopCount: Int): Map<String, Any> =
        if (sinceLaunchMs in 0 until WINDOW_MS) {
            mapOf("crash.launch" to true, "crash.since_launch_ms" to sinceLaunchMs, "crash.loop_count" to loopCount)
        } else {
            emptyMap()
        }
}
