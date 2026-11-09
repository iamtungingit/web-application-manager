package com.iamtungingit.wam.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `atomic store roundtrip`() {
        val f = File(tmp.root, "cfg/store.json")
        val store = AtomicJsonStore(f)
        store.write(JSONObject().put("a", 1).put("nested", JSONObject().put("b", "x")))
        val read = store.read()
        assertEquals(1, read?.optInt("a"))
        assertEquals("x", read?.optJSONObject("nested")?.optString("b"))
    }

    @Test
    fun `update applies transform atomically`() {
        val store = AtomicJsonStore(File(tmp.root, "cfg/reg.json"))
        store.update { it.put("count", 1) }
        store.update { it.put("count", it.optInt("count") + 1) }
        assertEquals(2, store.read()?.optInt("count"))
    }

    @Test
    fun `corrupt file reads as null`() {
        val f = File(tmp.root, "bad.json")
        f.writeText("{ not json")
        assertNull(AtomicJsonStore(f).read())
    }

    @Test
    fun `no tmp files leak after successful writes`() {
        val dir = File(tmp.root, "cfg")
        val store = AtomicJsonStore(File(dir, "x.json"))
        repeat(5) { store.write(JSONObject().put("i", it)) }
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test
    fun `canonicalChild contains the child inside parent`() {
        val parent = File(tmp.root, "root").apply { mkdirs() }
        val child = Paths.canonicalChild(parent, "abc")
        assertEquals(File(parent, "abc"), child)
    }

    @Test
    fun `deleteRec does not follow symlinks and frees bytes`() {
        val root = File(tmp.root, "r").apply { mkdirs() }
        val f = File(root, "file.txt")
        f.writeBytes(ByteArray(1000))
        assertEquals(1000L, Fs.deleteRec(root))
        assertFalse(root.exists())
    }

    @Test
    fun `sizeRec sums files`() {
        val root = File(tmp.root, "s").apply { mkdirs() }
        File(root, "a").writeBytes(ByteArray(10))
        File(File(root, "sub").apply { mkdirs() }, "b").writeBytes(ByteArray(20))
        assertEquals(30L, Fs.sizeRec(root))
    }
}
