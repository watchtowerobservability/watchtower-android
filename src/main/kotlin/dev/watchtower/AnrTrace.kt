package dev.watchtower

/**
 * The ART thread dump `ApplicationExitInfo.getTraceInputStream()` returns for an ANR: the main
 * thread's Java frames become the error stack (where the app was stuck) and the other threads'
 * frames the `error.threads` text, in the same shape [Threads.others] writes.
 *
 * A thread block starts with `"name" prio=5 tid=1 Sleeping` and ends at a blank line; its Java
 * frames are the `  at pkg.Class.method(File.kt:12)` lines (native frames and lock notes are skipped).
 */
internal object AnrTrace {
    class Parsed(val mainStack: String, val threads: String)

    private val header = Regex("^\"(.*)\" (.*)$")

    fun parse(text: String, maxThreads: Int = 12, maxFrames: Int = 12): Parsed? {
        var main: String? = null
        val others = mutableListOf<String>()
        var name: String? = null
        var state = ""
        val frames = mutableListOf<String>()
        fun flush() {
            val n = name ?: return
            if (frames.isEmpty()) return
            if (n == "main") {
                main = frames.joinToString("\n") { "  $it" }.take(8000)
            } else if (others.size < maxThreads && !n.startsWith("wt-")) {
                others += "Thread $n ($state)\n" + frames.take(maxFrames).joinToString("\n") { "  $it" }
            }
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val h = header.matchEntire(line)
            if (h != null) {
                flush()
                name = h.groupValues[1]
                state = h.groupValues[2].substringAfterLast(' ')
                frames.clear()
            } else if (line.isEmpty()) {
                flush()
                name = null
                frames.clear()
            } else if (name != null && line.startsWith("at ")) {
                frames += line
            }
        }
        flush()
        val m = main ?: return null
        return Parsed(m, others.joinToString("\n\n").take(6000))
    }
}
