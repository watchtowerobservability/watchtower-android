package dev.watchtower

/** Stacks of the threads other than the crashing one, so a crash report shows what else was running. */
internal object Threads {
    /** At most [maxThreads] threads, [maxFrames] frames each, as "Thread name (state)\n at ..." blocks. */
    fun others(
        except: Thread?,
        maxThreads: Int = 12,
        maxFrames: Int = 12,
        all: Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces(),
    ): String = all.entries
        .filter { it.key !== except && it.value.isNotEmpty() && !it.key.name.startsWith("wt-") }
        .sortedBy { it.key.name }
        .take(maxThreads)
        .joinToString("\n\n") { (t, st) ->
            "Thread ${t.name} (${t.state})\n" + st.take(maxFrames).joinToString("\n") { "  at $it" }
        }.take(6000)
}
