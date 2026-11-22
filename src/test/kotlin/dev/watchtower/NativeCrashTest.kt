package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class NativeCrashTest {
    private val run = RunState("s1", 42, 1_000, 5_000, foreground = true)

    @Test
    fun reportBecomesOneErrorEventWithTombstoneFrames() {
        val report = "signal=11\ncode=1\naddr=0x0\n" +
            "frame=0x7f00a4 /data/app/lib/arm64/libgame.so 0x4f0a4 crash_here\n" +
            "frame=0x7f1000 /system/lib64/libc.so 0x1000\nframe=0x99\n"
        val e = NativeCrash.event(report, run)!!
        assertEquals("NativeCrash", e.name)
        assertEquals("SIGSEGV", e.attrs["error.type"])
        assertEquals("SIGSEGV (code 1) at 0x0", e.attrs["error.message"])
        assertEquals(5_000L * 1_000_000L, e.tsNs)
        val stack = (e.attrs["error.stack"] as String).lines()
        assertEquals("#00 pc 000000000004f0a4  /data/app/lib/arm64/libgame.so (crash_here)", stack[0])
        assertEquals("#01 pc 0000000000001000  /system/lib64/libc.so", stack[1])
        assertEquals("#02 pc 0000000000000099  <unknown>", stack[2])
        assertNull(NativeCrash.event("garbage\n", run))
    }

    @Test
    fun buildIdsAndOtherThreadsBecomeWireFrames() {
        val report = "signal=6\ncode=-6\naddr=0x0\n" +
            "frame=0x7f00a4 /data/app/lib/arm64/libgame.so 0x4f0a4 crash_here\nbid=0123456789abcdef0123456789abcdef01234567\n" +
            "frame=0x99\n" +
            "thread=77\nframe=0x7f1000 /system/lib64/libc.so 0x1000 poll\nbid=deadbeef\n"
        val e = NativeCrash.event(report, run)!!
        @Suppress("UNCHECKED_CAST")
        val frames = e.attrs["frames"] as List<Map<String, Any>>
        assertEquals(1, frames.size) // the frame with no module cannot be resolved
        assertEquals("crash_here", frames[0]["function"])
        assertEquals("0x4f0a4", frames[0]["instruction_addr"])
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", frames[0]["debug_id"])
        @Suppress("UNCHECKED_CAST")
        val t = (e.attrs["threads"] as List<Map<String, Any>>).single()
        assertEquals(77L, t["id"])
        assertEquals("deadbeef-0000-0000-0000-000000000000", ((t["frames"] as List<*>)[0] as Map<*, *>)["debug_id"])
        assertEquals(2, (e.attrs["error.stack"] as String).lines().size) // threads stay out of the crash stack
    }

    @Test
    fun reportIsReadOnceAndLibraryIsOptional() {
        val dir = Files.createTempDirectory("wt-ndk").toFile()
        assertFalse(NativeCrash.install(dir)) // no libwatchtower-ndk.so on the test JVM
        java.io.File(dir, "wt-native-crash.txt").writeText("signal=6\n")
        assertEquals("signal=6\n", NativeCrash.take(dir))
        assertNull(NativeCrash.take(dir))
    }
}
