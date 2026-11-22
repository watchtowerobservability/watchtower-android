package dev.watchtower

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRumTest {
    private val cfg = Config(
        key = "k", service = "shop", endpoint = "https://edge.example",
        propagateTo = listOf("https://api.example/"),
    )
    private val run = RunState("s1", 42, 1_000, 5_000, foreground = true)

    @Test
    fun exitReasonsMapToSessionEndReasons() {
        assertEquals("crash", SessionEnd.reason(ApplicationExitInfo.REASON_CRASH, run))
        assertEquals("native_crash", SessionEnd.reason(ApplicationExitInfo.REASON_CRASH_NATIVE, run))
        assertEquals("anr", SessionEnd.reason(ApplicationExitInfo.REASON_ANR, run))
        assertEquals("oom", SessionEnd.reason(ApplicationExitInfo.REASON_LOW_MEMORY, run))
        assertEquals("user_exit", SessionEnd.reason(ApplicationExitInfo.REASON_USER_REQUESTED, run))
        assertEquals("user_exit", SessionEnd.reason(ApplicationExitInfo.REASON_EXIT_SELF, run))
        assertEquals("background_timeout", SessionEnd.reason(ApplicationExitInfo.REASON_SIGNALED, run.copy(foreground = false)))
        // before API 30 there is no exit record: only the run state decides
        assertEquals("crash", SessionEnd.reason(null, run.copy(crashed = true)))
        assertEquals("anr", SessionEnd.reason(null, run.copy(hanging = true)))
        assertEquals("user_exit", SessionEnd.reason(null, run))
        assertEquals("background_timeout", SessionEnd.reason(null, run.copy(foreground = false)))
    }

    @Test
    fun exitRecordAddsOnlyFailuresTheSdkDidNotSeeLive() {
        val native = SessionEnd.errorFor("native_crash", run, "signal 11", "AAEC")!!
        assertEquals("native", native.attrs["crash.class"])
        assertEquals("AAEC", native.attrs["crash.tombstone_b64"])
        assertEquals(5_000L * 1_000_000L, native.tsNs)
        assertEquals("anr", SessionEnd.errorFor("anr", run, null, "main")!!.attrs["crash.class"])
        assertNull("the watchdog already reported it", SessionEnd.errorFor("anr", run.copy(hanging = true), null, null))
        assertNull("background kills reclaim memory", SessionEnd.errorFor("oom", run.copy(foreground = false), null, null))
        assertEquals("OutOfMemory", SessionEnd.errorFor("oom", run, null, null)!!.name)
        assertNull(SessionEnd.errorFor("crash", run, null, null))
        val end = SessionEnd.endEvent("oom", run).toJson()
        assertEquals("action", end.getString("type"))
        assertEquals("oom", end.getJSONObject("attrs").getString("session.end_reason"))
    }

    @Test
    fun runStateRoundTripsAndRejectsGarbage() {
        val s = run.copy(crashed = true)
        assertEquals(s, RunState.parse(s.toJson()))
        assertNull(RunState.parse("{"))
    }

    @Test
    fun resourceUrlsLoseQueryAndFragment() {
        assertEquals("https://api.example/a", Network.scrubUrl("https://api.example/a?token=x#y"))
        assertEquals("https://api.example/a", Network.scrubUrl("https://api.example/a#y"))
        val e = Network.resourceEvent("https://api.example/a?q=1", "get", 503, 12, 10, 7, "a".repeat(32) to "b".repeat(16))
        val j = e.toJson()
        assertEquals("resource", j.getString("type"))
        assertEquals(12.0, j.getDouble("value"), 0.0)
        assertEquals("b".repeat(16), j.getString("span_id"))
        val a = j.getJSONObject("attrs")
        assertEquals("https://api.example/a", a.getString("url"))
        assertEquals("GET", a.getString("method"))
        assertTrue(a.getBoolean("failed"))
        assertTrue(Network.resourceEvent("u", "GET", null, 1, null, 1, null).attrs["failed"] == true)
    }

    @Test
    fun traceparentOnlyToAllowedHostsNeverTheEdge() {
        assertTrue(Network.shouldPropagate("https://api.example/v1/cart", cfg))
        assertFalse(Network.shouldPropagate("https://cdn.other/x.js", cfg))
        assertFalse(Network.shouldPropagate("https://edge.example/v1/rum/events", cfg.copy(propagateTo = listOf("https://"))))
        assertFalse(Network.shouldPropagate("https://api.example/", cfg.copy(propagateTo = emptyList())))
        val (t, s) = Network.hex(16) to Network.hex(8)
        assertEquals(t to s, Network.parseTraceparent(Network.traceparent(t, s)))
        assertNull(Network.parseTraceparent("00-zz-yy-01"))
    }

    @Test
    fun viewsCarryRouteUrlAndTimeSpentOnThePreviousScreen() {
        val v = ViewTracker("shop")
        val first = v.appeared("HomeActivity", 1_000)!!
        assertEquals("view", first.type)
        assertEquals("app://shop/HomeActivity", first.attrs["url"])
        assertFalse(first.attrs.containsKey("previous_route"))
        assertNull("same screen again is not a new view", v.appeared("HomeActivity", 1_100))
        v.disappeared("HomeActivity", 3_000)
        val next = v.appeared("CartActivity", 3_050)!!
        assertEquals("HomeActivity", next.attrs["previous_route"])
        assertEquals(2_000L, next.attrs["previous_duration_ms"])
        assertEquals("CartActivity", v.current)
    }

    @Test
    fun postsCarryThePackageNameForTheEdgeAppIdCheck() {
        val dir = java.nio.file.Files.createTempDirectory("wt").toFile()
        val t = Transport(cfg, OfflineBuffer(dir, 1024), "s1", emptyMap(), "com.example.shop")
        val h = t.headers(gzip = false)
        assertEquals("Bearer k", h["Authorization"])
        assertEquals("com.example.shop", h[APP_ID_HEADER])
        assertNull(h["Origin"])
        assertNull(h["Content-Encoding"])
        assertNull(Transport(cfg, OfflineBuffer(dir, 1024), "s1", emptyMap()).headers(true)[APP_ID_HEADER])
    }
}
