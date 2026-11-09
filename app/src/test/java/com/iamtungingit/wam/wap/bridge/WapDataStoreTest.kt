package com.iamtungingit.wam.wap.bridge

import com.iamtungingit.wam.wap.archive.SizeLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WapDataStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(quota: Long = 10L * 1024 * 1024): WapDataStore =
        WapDataStore(File(tmp.root, "data"), quota)

    @Test
    fun `valid names accepted invalid rejected`() {
        val s = store()
        assertTrue(s.writeText("score.json", "123").ok)
        assertFalse(s.writeText("../escape", "x").ok)
        assertFalse(s.writeText("a/b.json", "x").ok)
        assertFalse(s.writeText(".hidden", "x").ok)
        assertFalse(s.writeText("", "x").ok)
    }

    @Test
    fun `read write delete roundtrip`() {
        val s = store()
        s.writeText("a.txt", "hello")
        assertEquals("hello", s.readText("a.txt"))
        assertTrue(s.delete("a.txt"))
        assertNull(s.readText("a.txt"))
    }

    @Test
    fun `file size cap`() {
        val s = store()
        val big = "x".repeat(SizeLimits.BRIDGE_MAX_FILE + 1)
        assertFalse(s.writeText("big.txt", big).ok)
    }

    @Test
    fun `quota enforced`() {
        val s = store(quota = 1000)
        assertTrue(s.writeText("a.txt", "y".repeat(600)).ok)
        assertFalse(s.writeText("b.txt", "y".repeat(600)).ok)
        // replace works within quota
        assertTrue(s.writeText("a.txt", "y".repeat(900)).ok)
    }

    @Test
    fun `file count cap`() {
        val s = store()
        for (i in 0 until SizeLimits.BRIDGE_MAX_FILES) {
            assertTrue(s.writeText("f$i.txt", "x").ok)
        }
        assertFalse(s.writeText("extra.txt", "x").ok)
    }

    @Test
    fun `usage reports bytes and files`() {
        val s = store()
        s.writeText("a.txt", "12345")
        val (bytes, files) = s.usage()
        assertEquals(5L, bytes)
        assertEquals(1, files)
    }

    @Test
    fun `list and deleteAll`() {
        val s = store()
        s.writeText("a.txt", "1")
        s.writeText("b.txt", "2")
        assertEquals(listOf("a.txt", "b.txt"), s.list())
        s.deleteAll()
        assertEquals(0, s.list().size)
    }

    @Test
    fun `protocol roundtrip`() {
        val req = BridgeProtocol.parse("""{"id":7,"method":"data.list","args":{}}""")
        assertEquals(7L, req?.id)
        assertEquals("data.list", req?.method)
        assertNull(BridgeProtocol.parse("not json"))
        assertNull(BridgeProtocol.parse("""{"id":-1,"method":"x"}"""))
        val ok = BridgeProtocol.okJson(7L, true)
        assertTrue(ok.contains("\"ok\":true"))
        val err = BridgeProtocol.errJson(7L, "nope")
        assertTrue(err.contains("\"ok\":false"))
    }

    @Test
    fun `rate limiter`() {
        val rl = BridgeProtocol.RateLimiter(3)
        assertTrue(rl.allow())
        assertTrue(rl.allow())
        assertTrue(rl.allow())
        assertFalse(rl.allow())
    }
}
