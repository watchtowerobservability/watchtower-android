package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaTest {
    private fun t(name: String) = Thread(name)

    @Test
    fun otherThreadsSkipTheCrashingOneAndTheSdksOwn() {
        val crashing = t("main"); val worker = t("worker"); val own = t("wt-io")
        val st = arrayOf(StackTraceElement("a.B", "run", "B.kt", 3))
        val out = Threads.others(crashing, all = mapOf(crashing to st, worker to st, own to st, t("idle") to emptyArray()))
        assertTrue(out.startsWith("Thread worker"))
        assertFalse(out.contains("main"))
        assertFalse(out.contains("wt-io"))
        assertFalse(out.contains("idle"))
        assertTrue(out.contains("at a.B.run(B.kt:3)"))
    }

    @Test
    fun foldedStacksCountRepeatsAndStayUnderTheLimit() {
        val a = listOf("main", "init", "load")
        val b = listOf("main", "init")
        val folded = LaunchSampler.fold(listOf(a, a, b, emptyList()))
        assertEquals("main;init;load 2\nmain;init 1", folded)
        assertTrue(LaunchSampler.fold(List(500) { listOf("f$it") }, limit = 100).length <= 100)
    }

    @Test
    fun fragmentScreenUsesTheSimpleName() {
        assertEquals("String", fragmentScreen(String::class.java))
    }

    @Test
    fun errorsCarryTheBuildIdForSymbolication() {
        val cfg = Config(key = "k", service = "s", buildId = "map-uuid")
        assertEquals(mapOf<String, Any>("debug.build_id" to "map-uuid"), Watchtower.errorLinks(cfg, "s1"))
        val rep = cfg.copy(replay = ReplayConfig(enabled = true))
        assertEquals("s1", Watchtower.errorLinks(rep, "s1")["replay.session_id"])
        assertFalse(Config(key = "k", service = "s").profileLaunch)
    }

    @Test
    fun anrTraceGivesTheMainStackAndTheOtherThreads() {
        val dump = """
            ----- pid 12 at 2026-11-08 10:00:00 -----
            Cmd line: dev.app

            "main" prio=5 tid=1 Sleeping
              | group="main" sCount=1
              at java.lang.Thread.sleep(Native method)
              - sleeping on <0x1> (a java.lang.Object)
              at dev.app.Main.run(Main.kt:12)
              native: #00 pc 0000 /apex/libart.so

            "pool-1" prio=5 tid=7 Waiting
              at java.lang.Object.wait(Object.java:10)

            "wt-io" prio=5 tid=9 Waiting
              at java.lang.Object.wait(Object.java:10)

            ----- end 12 -----
        """.trimIndent()
        val p = AnrTrace.parse(dump)!!
        assertTrue(p.mainStack.contains("at dev.app.Main.run(Main.kt:12)"))
        assertTrue(p.threads.startsWith("Thread pool-1 (Waiting)"))
        assertFalse(p.threads.contains("wt-io"))
        assertTrue(AnrTrace.parse("not a dump") == null)
        val e = SessionEnd.errorFor("anr", RunState("s", 1, 1, 1), null, dump)!!
        assertEquals(p.threads, e.attrs["error.threads"])
    }
}
