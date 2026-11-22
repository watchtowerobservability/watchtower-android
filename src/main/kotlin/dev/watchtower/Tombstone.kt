package dev.watchtower

/**
 * Decoder for the protobuf tombstone that `ApplicationExitInfo.getTraceInputStream()` returns for
 * a native crash on API 31+ (`system/core/debuggerd/proto/tombstone.proto`). It reads only what a
 * crash view needs: the signal, the abort message, the crashing thread's backtrace and a short
 * backtrace of each other thread, so the SDK can send a full stack instead of the first kilobytes
 * of raw bytes.
 *
 * Field numbers used: Tombstone `tid` 6, `signal_info` 10, `abort_message` 14, `threads` 16 (a
 * map entry: key 1, Thread 2); Thread `id` 1, `name` 2, `current_backtrace` 4; BacktraceFrame `rel_pc` 1,
 * `function_name` 4, `function_offset` 5, `file_name` 6, `build_id` 8; Signal `number` 1, `name` 2,
 * `code_name` 4, `has_fault_address` 8, `fault_address` 9.
 *
 * A stream cut short still decodes up to the cut: a field that runs past the data ends the walk.
 * shortcut: the register dump and memory dumps are dropped; upgrade when the crash view shows registers.
 */
object Tombstone {
    const val MAX_FRAMES = 64
    const val MAX_OTHER_THREADS = 8
    const val MAX_OTHER_FRAMES = 16

    /** One thread of the tombstone: its id, name and backtrace (innermost frame first). */
    class Thr(val id: Long, val name: String, val frames: List<Frame>)

    class Frame(val relPc: Long, val function: String, val offset: Long, val file: String, val buildId: String) {
        /** `#00 pc 000000000004f2a4  /system/lib64/libc.so (abort+164) (BuildId: ab12)`. */
        fun line(i: Int): String {
            val fn = if (function.isEmpty()) "" else " ($function${if (offset > 0) "+$offset" else ""})"
            val id = if (buildId.isEmpty()) "" else " (BuildId: $buildId)"
            return "#${i.toString().padStart(2, '0')} pc ${relPc.toString(16).padStart(16, '0')}  ${file.ifEmpty { "<unknown>" }}$fn$id"
        }
    }

    class Decoded(
        val signal: String,
        val faultAddr: Long?,
        val abort: String,
        val frames: List<Frame>,
        val others: List<Thr> = emptyList(),
    ) {
        val message: String
            get() = buildString {
                append(signal.ifEmpty { "native crash" })
                faultAddr?.let { append(" at 0x").append(it.toString(16)) }
                if (abort.isNotEmpty()) append(": ").append(abort)
            }

        /** The backtrace as tombstone text, innermost frame first. */
        val stack: String get() = frames.mapIndexed { i, f -> f.line(i) }.joinToString("\n")
    }

    /** A bounds-checked protobuf cursor over `b[p until end]`. */
    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end

        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                if (p >= end || shift > 63) throw IndexOutOfBoundsException()
                val x = b[p++].toInt()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x >= 0) return v
                shift += 7
            }
        }

        /** The next length-delimited payload as a sub-reader; one cut short is clamped to the data. */
        fun bytes(): Reader {
            val n = varint()
            if (n < 0) throw IndexOutOfBoundsException()
            val len = minOf(n, (end - p).toLong()).toInt()
            return Reader(b, p, p + len).also { p += len }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> bytes()
                5 -> advance(4)
                else -> throw IndexOutOfBoundsException()
            }
        }

        private fun advance(n: Int) {
            if (n > end - p) throw IndexOutOfBoundsException()
            p += n
        }

        fun string(): String = bytes().let { String(b, it.p, it.end - it.p, Charsets.UTF_8) }
    }

    /** The decoded tombstone, or null when [bytes] is no tombstone with a backtrace. */
    fun decode(bytes: ByteArray): Decoded? {
        var tid = -1L
        var signal = ""
        var fault: Long? = null
        var abort = ""
        val threads = mutableListOf<Thr>()
        val top = Reader(bytes, 0, bytes.size)
        try {
            while (top.more()) {
                val tag = top.varint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                when {
                    field == 6 && wire == 0 -> tid = top.varint()
                    field == 10 && wire == 2 -> signalInfo(top.bytes()).let { signal = it.first; fault = it.second }
                    field == 14 && wire == 2 -> abort = top.string().trim()
                    field == 16 && wire == 2 -> thread(top.bytes())?.let { threads += it }
                    else -> top.skip(wire)
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // cut short: use what was read
        }
        val crashed = threads.firstOrNull { it.id == tid } ?: threads.firstOrNull { it.frames.isNotEmpty() }
        if (crashed == null || crashed.frames.isEmpty()) return null
        val others = threads.filter { it !== crashed && it.frames.isNotEmpty() }.take(MAX_OTHER_THREADS)
            .map { Thr(it.id, it.name, it.frames.take(MAX_OTHER_FRAMES)) }
        return Decoded(signal, fault, abort, crashed.frames.take(MAX_FRAMES), others)
    }

    private fun signalInfo(r: Reader): Pair<String, Long?> {
        var name = ""
        var code = ""
        var has = false
        var addr = 0L
        try {
            while (r.more()) {
                val tag = r.varint().toInt()
                when {
                    tag == (2 shl 3 or 2) -> name = r.string()
                    tag == (4 shl 3 or 2) -> code = r.string()
                    tag == (8 shl 3) -> has = r.varint() != 0L
                    tag == (9 shl 3) -> addr = r.varint()
                    else -> r.skip(tag and 7)
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // keep what was read
        }
        return (if (code.isEmpty()) name else "$name ($code)") to (if (has) addr else null)
    }

    /** One `threads` map entry: the thread id and its backtrace. */
    private fun thread(entry: Reader): Thr? {
        var value: Reader? = null
        try {
            while (entry.more()) {
                val tag = entry.varint().toInt()
                if (tag == (2 shl 3 or 2)) value = entry.bytes() else entry.skip(tag and 7)
            }
        } catch (_: IndexOutOfBoundsException) {
            // keep what was read
        }
        val t = value ?: return null
        var id = -1L
        var name = ""
        val frames = mutableListOf<Frame>()
        try {
            while (t.more()) {
                val tag = t.varint().toInt()
                when {
                    tag == (1 shl 3) -> id = t.varint()
                    tag == (2 shl 3 or 2) -> name = t.string()
                    tag == (4 shl 3 or 2) && frames.size < MAX_FRAMES -> frames += frame(t.bytes())
                    else -> t.skip(tag and 7)
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // keep what was read
        }
        return Thr(id, name, frames)
    }

    private fun frame(r: Reader): Frame {
        var pc = 0L
        var fn = ""
        var off = 0L
        var file = ""
        var build = ""
        try {
            while (r.more()) {
                val tag = r.varint().toInt()
                when (tag) {
                    1 shl 3 -> pc = r.varint()
                    4 shl 3 or 2 -> fn = r.string()
                    5 shl 3 -> off = r.varint()
                    6 shl 3 or 2 -> file = r.string()
                    8 shl 3 or 2 -> build = r.string()
                    else -> r.skip(tag and 7)
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            // keep what was read
        }
        return Frame(pc, fn, off, file, build)
    }

    /** The ELF build id as the 16-byte debug id symbol files are keyed by (padded or cut). */
    fun debugId(buildId: String): String? {
        if (buildId.length < 8 || buildId.length % 2 != 0 || !buildId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val h = buildId.lowercase().padEnd(32, '0').take(32)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** The error event attributes of a decoded crash. */
    fun attrs(d: Decoded): Map<String, Any> {
        val base = mapOf<String, Any>(
            "error.message" to d.message, "message" to d.message,
            "error.stack" to d.stack, "stack" to d.stack,
            "frames" to wireFrames(d),
        )
        return if (d.others.isEmpty()) base else base + ("threads" to wireThreads(d))
    }

    /** The other threads as `contexts.threads` entries (`id`, `name`, `frames`), which the server symbolicates too. */
    fun wireThreads(d: Decoded): List<Map<String, Any>> = d.others.map { t ->
        mapOf("id" to t.id, "name" to t.name, "frames" to wire(t.frames))
    }

    /** The frames as `FrameV1` objects, oldest call first, which the server symbolicates by debug id. */
    fun wireFrames(d: Decoded): List<Map<String, Any>> = wire(d.frames)

    private fun wire(frames: List<Frame>): List<Map<String, Any>> = frames.reversed().map { f ->
        buildMap {
            put("function", f.function)
            put("module", f.file)
            put("instruction_addr", "0x" + f.relPc.toString(16))
            debugId(f.buildId)?.let { put("debug_id", it) }
        }
    }
}
