package dev.watchtower

import java.io.File

/**
 * NDK crash capture (SDK-003). `libwatchtower-ndk.so` (src/main/cpp, built with
 * `-Pwatchtower.ndk=true`) installs signal handlers that write a short report of a native crash;
 * the next launch turns it into one `NativeCrash` error event of the crashed session, with the
 * signal and the frames as `error.stack` in tombstone form (`#00 pc <offset>  <module>`), which
 * works below API 30 where no exit record exists. Without the library this is a no-op.
 */
object NativeCrash {
    private const val FILE = "wt-native-crash.txt"
    private val SIGNALS = mapOf(4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 11 to "SIGSEGV")

    @JvmStatic
    private external fun nativeInstall(path: String)

    /** Loads the handler library, if the app ships it, and points it at [dir]. */
    fun install(dir: File): Boolean = runCatching {
        System.loadLibrary("watchtower-ndk")
        nativeInstall(File(dir, FILE).path)
        true
    }.getOrDefault(false)

    /** The report the previous run left in [dir], removed once read. */
    fun take(dir: File): String? {
        val f = File(dir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    /** One reported frame: its `frame=` fields and the `bid=` (build id) line that follows it. */
    private class Fr(val line: String, var bid: String = "")

    /** The error event of a report, or null when it names no signal. */
    fun event(report: String, prev: RunState): Event? {
        var signal: Int? = null
        var code = ""
        var addr = ""
        val frames = mutableListOf<Fr>()
        val threads = mutableListOf<Pair<Long, MutableList<Fr>>>()
        for (line in report.lineSequence()) {
            val (k, v) = line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: continue
            val into = threads.lastOrNull()?.second ?: frames
            when (k) {
                "signal" -> signal = v.toIntOrNull()
                "code" -> code = v
                "addr" -> addr = v
                "frame" -> into += Fr(v)
                "bid" -> into.lastOrNull()?.bid = v
                "thread" -> if (threads.size < MAX_THREADS) threads += ((v.toLongOrNull() ?: -1L) to mutableListOf<Fr>())
            }
        }
        val sig = signal ?: return null
        val name = SIGNALS[sig] ?: "signal $sig"
        val attrs = mutableMapOf<String, Any>(
            "error.message" to "$name (code $code) at $addr", "error.type" to name,
            "crash.class" to "native", "crash.handled" to false, "crash.previous_session" to true,
            "app.in_foreground" to prev.foreground,
        )
        if (frames.isNotEmpty()) {
            attrs["error.stack"] = frames.mapIndexed { i, f -> frameLine(i, f.line) }.joinToString("\n")
            attrs["frames"] = wire(frames)
        }
        val others = threads.filter { it.second.isNotEmpty() }
        if (others.isNotEmpty()) {
            attrs["threads"] = others.map { (tid, fs) -> mapOf("id" to tid, "frames" to wire(fs)) }
        }
        return Event("error", "NativeCrash", attrs = attrs, tsNs = prev.lastMs * 1_000_000L)
    }

    private const val MAX_THREADS = 16

    /** The frames as `FrameV1` objects, oldest call first; the build id is the debug id the server symbolicates by. */
    private fun wire(frames: List<Fr>): List<Map<String, Any>> = frames.reversed().mapNotNull { f ->
        val p = f.line.split(' ')
        if (p.size < 3) return@mapNotNull null // no module: nothing to resolve
        buildMap {
            put("function", p.getOrElse(3) { "" })
            put("module", p[1])
            put("instruction_addr", p[2])
            Tombstone.debugId(f.bid)?.let { put("debug_id", it) }
        }
    }

    /** `0x<pc> <module> 0x<offset> [symbol]` as a tombstone backtrace line. */
    private fun frameLine(i: Int, v: String): String {
        val p = v.split(' ')
        val n = i.toString().padStart(2, '0')
        if (p.size < 3) return "#$n pc ${p[0].removePrefix("0x").padStart(16, '0')}  <unknown>"
        val off = p[2].removePrefix("0x").padStart(16, '0')
        val sym = if (p.size > 3) " (${p[3]})" else ""
        return "#$n pc $off  ${p[1]}$sym"
    }
}
