package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopTest {
    private val run = RunState("s1", 42, 1_000, 3_000, foreground = true) // last sign of life 2 s in

    @Test
    fun launchCrashesCountUntilARunSurvivesTheWindow() {
        assertEquals(1, CrashLoop.count(run.copy(crashed = true), true))
        assertEquals(3, CrashLoop.count(run.copy(launchCrashes = 2), true))
        assertEquals("a run that did not crash resets", 0, CrashLoop.count(run.copy(launchCrashes = 2), false))
        assertEquals("crashed 10 s in", 0, CrashLoop.count(run.copy(lastMs = 11_000), true))
        assertEquals(0, CrashLoop.count(null, true))
        assertEquals(2, RunState.parse(run.copy(launchCrashes = 2).toJson())!!.launchCrashes)
        assertEquals("older state files", 0, RunState.parse("""{"session":"s","pid":1,"start_ms":1,"last_ms":2}""")!!.launchCrashes)
    }

    @Test
    fun launchCrashTagsOnlyInsideTheWindow() {
        val a = CrashLoop.attrs(1_234, 2)
        assertEquals(true, a["crash.launch"])
        assertEquals(1_234L, a["crash.since_launch_ms"])
        assertEquals(2, a["crash.loop_count"])
        assertTrue(CrashLoop.attrs(5_000, 1).isEmpty())
    }

    @Test
    fun feedbackBodyTrimsCutsAndLinksOnlyAValidEventId() {
        val b = feedbackBody("  broken  ", " Ann ", "", "0A1B2C3D-0000-4000-8000-00000000000F")
        assertEquals(mapOf("message" to "broken", "event_id" to "0a1b2c3d00004000800000000000000f", "name" to "Ann"), b)
        assertNull(feedbackBody("   ", null, null, null))
        assertNull(feedbackBody("x", null, null, "nope")!!["event_id"])
        assertEquals(4096, feedbackBody("a".repeat(5000), null, null, null)!!["message"]!!.length)
    }
}
