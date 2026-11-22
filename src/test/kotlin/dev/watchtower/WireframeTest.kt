package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireframeTest {
    private fun node(id: Int, text: String? = null, kind: String = "text") = WireNode(id, 0, id * 10, 50, 10, kind, "#ffffff", text)

    private fun payload(e: org.json.JSONObject?) = e!!.getJSONObject("data").getJSONObject("payload")

    @Test
    fun textIsHiddenByDefaultAndPasswordsAlways() {
        assertTrue(hidesText(Kind.TEXT, maskText = true))
        assertFalse(hidesText(Kind.TEXT, maskText = false))
        assertFalse(hidesText(Kind.TEXT, maskText = true, unmask = true))
        assertTrue("unmask never lifts an input", hidesText(Kind.INPUT, maskText = true, unmask = true))
        assertTrue(hidesText(Kind.PASSWORD, maskText = false, unmask = true))
        assertTrue(hidesText(Kind.TEXT, maskText = false, forceMask = true))
        assertEquals("*******", wireText("hunter2", hide = true))
        assertEquals(200, wireText("a".repeat(500), hide = false).length)
        assertEquals(64, wireText("a".repeat(500), hide = true).length)
    }

    @Test
    fun fullThenDiffThenNothing() {
        val e = WireframeEncoder(keyEvery = 3)
        val a = listOf(node(1), node(2, "**"))
        val f1 = payload(e.encode(a, 100, 200, "Home", 1))
        assertTrue(f1.getBoolean("full"))
        assertTrue(f1.getBoolean("masked"))
        assertEquals(2, f1.getJSONArray("nodes").length())
        assertEquals("wt.wireframe", e.encode(listOf(node(1)), 1, 1, "S", 5)!!.getJSONObject("data").getString("tag"))
        val e2 = WireframeEncoder(keyEvery = 3)
        e2.encode(a, 100, 200, "Home", 1)
        assertNull("no change, nothing sent", e2.encode(a, 100, 200, "Home", 2))
        val p2 = payload(e2.encode(listOf(node(1), node(2, "***")), 100, 200, "Home", 3))
        assertFalse(p2.getBoolean("full"))
        assertEquals(1, p2.getJSONArray("nodes").length())
        assertEquals(2, p2.getJSONArray("nodes").getJSONObject(0).getInt("id"))
        assertEquals("[1,2]", p2.getJSONArray("order").toString())
    }

    @Test
    fun removedViewChangesTheOrderOnly() {
        val e = WireframeEncoder()
        e.encode(listOf(node(1), node(2)), 1, 1, "S", 1)
        val p = payload(e.encode(listOf(node(1)), 1, 1, "S", 2))
        assertEquals("[1]", p.getJSONArray("order").toString())
        assertEquals(0, p.getJSONArray("nodes").length())
    }

    @Test
    fun colorsAndKinds() {
        assertEquals("#ff0080", hexColor(0xffff0080.toInt()))
        assertNull("transparent has no colour", hexColor(0))
        assertEquals("input", wireKind(Kind.PASSWORD))
        assertEquals("view", wireKind(Kind.OTHER))
        assertEquals(setOf("id", "x", "y", "w", "h", "k", "bg", "t"), WireframeEncoder.json(node(5, "x")).keys().asSequence().toSet())
    }
}
