package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SdkTest {
    private fun tree(vararg kids: Node) = Node(Kind.OTHER, 0, 0, 100, 200, children = kids.toList())

    @Test
    fun replayMasksTextAndInputsByDefault() {
        val r = maskRects(
            tree(
                Node(Kind.TEXT, 0, 0, 50, 10),
                Node(Kind.INPUT, 0, 20, 50, 10),
                Node(Kind.IMAGE, 0, 40, 50, 10),
            ),
        )
        assertEquals(listOf(Rect(0, 0, 50, 10), Rect(0, 20, 50, 10)), r)
    }

    @Test
    fun unmaskNeverLiftsInputsOrPasswords() {
        val r = maskRects(
            tree(
                Node(Kind.TEXT, 0, 0, 5, 5, unmask = true),
                Node(Kind.INPUT, 0, 10, 5, 5, unmask = true),
                Node(Kind.PASSWORD, 0, 20, 5, 5, unmask = true),
            ),
        )
        assertEquals(listOf(Rect(0, 10, 5, 5), Rect(0, 20, 5, 5)), r)
    }

    @Test
    fun passwordStaysMaskedWhenTextMaskingIsOff() {
        val r = maskRects(tree(Node(Kind.TEXT, 0, 0, 5, 5), Node(Kind.PASSWORD, 0, 10, 5, 5)), maskText = false)
        assertEquals(listOf(Rect(0, 10, 5, 5)), r)
    }

    @Test
    fun appAttributesCannotUseTheWtNamespace() {
        val a = safeAttrs(mapOf("wt.project_id" to "x", "screen" to "Home"))
        assertFalse(a.containsKey("wt.project_id"))
        assertTrue(a.containsKey("screen"))
    }

    @Test
    fun offlineBufferDropsOldestPastItsLimit() {
        val dir = Files.createTempDirectory("wt").toFile()
        val b = OfflineBuffer(dir, 25)
        repeat(5) { b.add("0123456789") }
        assertEquals(2, b.size())
        dir.deleteRecursively()
    }

    @Test
    fun screenshotEventCarriesMaskedFlag() {
        val e = screenshotEvent(1, "AA", 1, 1, "Home", 2, masked = true)
        assertEquals(5, e.getInt("type"))
        assertTrue(e.getJSONObject("data").getJSONObject("payload").getBoolean("masked"))
    }

    @Test
    fun coldLaunchHasPhasesThenWarmAfterBackground() {
        val l = LaunchTracker(processStartMs = 1_000, sdkStartMs = 1_200)
        l.activityCreated(1_500); l.activityStarted(1_550); l.activityResumed(1_600, "Home")
        val cold = l.firstFrame(1_700)!!
        assertEquals("app_launch_cold", cold.name)
        assertEquals(700.0, cold.value!!, 0.0)
        assertEquals(200L, cold.attrs["launch.phase.process_ms"])
        assertEquals(300L, cold.attrs["launch.phase.application_ms"])
        assertEquals(100L, cold.attrs["launch.phase.activity_ms"])
        assertEquals(100L, cold.attrs["launch.phase.first_frame_ms"])
        // a later draw is not a launch
        assertEquals(null, l.firstFrame(1_800))
        // rotation recreates the activity but is not a launch
        l.activityStopped(changingConfig = true); l.activityCreated(2_000); l.activityStarted(2_000); l.activityResumed(2_010, "Home")
        assertEquals(null, l.firstFrame(2_050))
        // back from the background
        l.activityStopped(); l.activityStarted(9_000); l.activityResumed(9_040, "Home")
        val warm = l.firstFrame(9_100)!!
        assertEquals("app_launch_warm", warm.name)
        assertEquals(100.0, warm.value!!, 0.0)
        assertEquals(false, warm.attrs["launch.activity_created"])
    }

    @Test
    fun backgroundStartedProcessIsNotACold() {
        val l = LaunchTracker(processStartMs = 0, sdkStartMs = 100)
        l.activityCreated(60_000); l.activityStarted(60_000); l.activityResumed(60_100, "Home")
        assertEquals("app_launch_warm", l.firstFrame(60_200)!!.name)
    }
}
