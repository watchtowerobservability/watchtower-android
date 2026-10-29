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
}
