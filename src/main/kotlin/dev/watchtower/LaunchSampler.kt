package dev.watchtower

import android.os.Looper

/**
 * Launch profiling (M14): samples the main thread's stack every [intervalMs] from SDK start until
 * the first frame, then reports the hottest folded stacks (`a;b;c count`, root first) as one
 * `app_launch_profile` vital. Opt-in with [Config.profileLaunch].
 */
internal class LaunchSampler(private val intervalMs: Long = 10, private val maxSamples: Int = 1000) {
    private val samples = ArrayList<Array<StackTraceElement>>()
    @Volatile private var running = false

    fun start() {
        running = true
        val main = Looper.getMainLooper().thread
        Thread({
            while (running && samples.size < maxSamples) {
                synchronized(samples) { samples.add(main.stackTrace) }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return@Thread }
            }
        }, "wt-launch-sampler").apply { isDaemon = true }.start()
    }

    /** Stops sampling; null when nothing was sampled. */
    fun stop(): Event? {
        running = false
        val folded = synchronized(samples) { fold(samples.map { st -> st.reversed().map { "${it.className}.${it.methodName}" } }) }
        if (folded.isEmpty()) return null
        return Event("vital", "app_launch_profile", samples.size.toDouble(), mapOf("profile.folded" to folded, "profile.interval_ms" to intervalMs))
    }

    companion object {
        /** Counts identical stacks and keeps the most frequent while the text fits the 2 KiB attribute limit. */
        fun fold(stacks: List<List<String>>, limit: Int = 2000): String {
            val counts = stacks.filter { it.isNotEmpty() }.groupingBy { it.joinToString(";") }.eachCount()
            val out = StringBuilder()
            for ((k, v) in counts.entries.sortedByDescending { it.value }) {
                val line = "$k $v\n"
                if (out.length + line.length > limit) { if (out.isEmpty()) out.append(line.take(limit)); break }
                out.append(line)
            }
            return out.toString().trimEnd()
        }
    }
}
