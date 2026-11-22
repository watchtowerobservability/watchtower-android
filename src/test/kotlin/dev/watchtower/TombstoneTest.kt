package dev.watchtower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds the protobuf bytes of a tombstone the way `tombstoned` writes them. */
private class Pb {
    val out = ByteArrayOutputStream()
    fun varint(v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }
    fun num(field: Int, v: Long) { varint((field shl 3).toLong()); varint(v) }
    fun bytes(field: Int, b: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, build: Pb.() -> Unit) = bytes(field, Pb().apply(build).out.toByteArray())
    fun toBytes(): ByteArray = out.toByteArray()
}

class TombstoneTest {
    private val buildId = "0123456789abcdef0123456789abcdef01234567"

    private fun tombstone(frames: Int): ByteArray = Pb().apply {
        num(6, 7) // tid
        msg(10) { num(1, 11); str(2, "SIGSEGV"); num(3, 1); str(4, "SEGV_MAPERR"); num(8, 1); num(9, 0xdead) }
        str(14, "fatal: boom")
        // another thread first: the crashing one is picked by tid
        msg(16) { num(1, 5); msg(2) { num(1, 5); msg(4) { num(1, 0x10); str(6, "/system/lib64/libc.so") } } }
        msg(16) {
            num(1, 7)
            msg(2) {
                num(1, 7)
                for (i in 0 until frames) {
                    msg(4) {
                        num(1, 0x4f0a4L + i)
                        num(2, 0x7f0000L + i)
                        str(4, "crash_here_$i")
                        num(5, 164)
                        str(6, "/data/app/lib/arm64/libgame.so")
                        str(8, buildId)
                    }
                }
            }
        }
    }.toBytes()

    @Test
    fun aTombstoneBeyondTheOldPrefixKeepsEveryFrame() {
        val raw = tombstone(40)
        assertTrue("fixture must exceed the old 1.5 KB prefix", raw.size > 1536)
        val d = Tombstone.decode(raw)!!
        assertEquals("SIGSEGV (SEGV_MAPERR) at 0xdead: fatal: boom", d.message)
        assertEquals(40, d.frames.size)
        assertEquals(
            "#00 pc 000000000004f0a4  /data/app/lib/arm64/libgame.so (crash_here_0+164) (BuildId: $buildId)",
            d.stack.lines()[0],
        )
        // oldest call first on the wire, with a debug id the symbolicator can look up
        val wire = Tombstone.wireFrames(d)
        assertEquals("crash_here_39", wire.first()["function"])
        assertEquals("0x4f0a4", wire.last()["instruction_addr"])
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", wire.last()["debug_id"])
        assertEquals(wire, Tombstone.attrs(d)["frames"])
    }

    @Test
    fun otherThreadsRideAlongWithTheirNames() {
        val raw = Pb().apply {
            num(6, 7)
            msg(16) { num(1, 5); msg(2) { num(1, 5); str(2, "RenderThread"); msg(4) { num(1, 0x10); str(6, "/system/lib64/libc.so"); str(8, "0123456789abcdef") } } }
            msg(16) { num(1, 7); msg(2) { num(1, 7); msg(4) { num(1, 0x20); str(6, "/data/libgame.so") } } }
        }.toBytes()
        val d = Tombstone.decode(raw)!!
        assertEquals(1, d.frames.size)
        assertEquals(listOf(5L), d.others.map { it.id })
        @Suppress("UNCHECKED_CAST")
        val t = (Tombstone.attrs(d)["threads"] as List<Map<String, Any>>).single()
        assertEquals("RenderThread", t["name"])
        assertEquals(5L, t["id"])
        assertEquals(1, (t["frames"] as List<*>).size)
    }

    @Test
    fun aCutOffStreamDecodesUpToTheCut() {
        val raw = tombstone(40)
        val d = Tombstone.decode(raw.copyOf(1536))
        assertNotNull(d)
        assertTrue(d!!.frames.isNotEmpty() && d.frames.size < 40)
        assertNull(Tombstone.decode(ByteArray(0)))
        assertNull(Tombstone.decode(byteArrayOf(0x7f, 0x7f, 0x7f)))
    }

    @Test
    fun debugIdIsCutOrPaddedLikeTheServer() {
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", Tombstone.debugId(buildId))
        assertEquals("deadbeef-0000-0000-0000-000000000000", Tombstone.debugId("deadbeef"))
        assertNull(Tombstone.debugId("zz"))
        assertNull(Tombstone.debugId(""))
    }
}
