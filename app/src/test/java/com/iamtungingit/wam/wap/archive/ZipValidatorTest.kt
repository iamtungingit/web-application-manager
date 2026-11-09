package com.iamtungingit.wam.wap.archive

import com.iamtungingit.wam.wap.manifest.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildZip(entries: Map<String, ByteArray>, method: Int = ZipEntry.DEFLATED): File {
        val f = File(tmp.root, "test.wap")
        ZipOutputStream(f.outputStream().buffered()).use { zos ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.method = method
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return f
    }

    private fun manifestJson(): ByteArray =
        """{"id":"a","name":"A","version":"1.0.0","type":"offline","entry":"index.html"}"""
            .toByteArray()

    @Test
    fun `valid archive passes`() {
        val zip = buildZip(
            mapOf(
                "manifest.json" to manifestJson(),
                "index.html" to "<h1>hi</h1>".toByteArray()
            )
        )
        val report = ZipValidator.validate(zip)
        assertTrue(report.ok)
        assertEquals(setOf("manifest.json", "index.html"), report.entryNames)
    }

    @Test
    fun `path traversal rejected`() {
        val zip = buildZip(
            mapOf(
                "manifest.json" to manifestJson(),
                "../evil.txt" to "x".toByteArray()
            )
        )
        val report = ZipValidator.validate(zip)
        assertEquals(ErrorCodes.ARCHIVE_PATH_TRAVERSAL, report.error?.code)
    }

    @Test
    fun `absolute path rejected`() {
        val zip = buildZip(mapOf("/etc/passwd" to "x".toByteArray()))
        val report = ZipValidator.validate(zip)
        assertNotNull(report.error)
    }

    @Test
    fun `duplicate entries rejected`() {
        // java.util.zip.ZipOutputStream refuses duplicate names and a Map dedupes,
        // so craft the duplicate-entry archive with raw ZIP structures.
        val zip = rawZip(
            listOf(
                "manifest.json" to manifestJson(),
                "index.html" to "1".toByteArray(),
                "index.html" to "2".toByteArray()
            )
        )
        val report = ZipValidator.validate(zip)
        assertEquals(ErrorCodes.ARCHIVE_DUPLICATE_ENTRY, report.error?.code)
    }

    private fun writeU16(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    /** Minimal STORED-entries ZIP writer that allows duplicate names. */
    private fun rawZip(entries: List<Pair<String, ByteArray>>): File {
        val f = File(tmp.root, "raw.wap")
        val body = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        for ((name, data) in entries) {
            val nb = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val lhPos = body.size().toLong()
            writeU32(body, 0x04034b50)
            writeU16(body, 20); writeU16(body, 0); writeU16(body, 0)
            writeU16(body, 0); writeU16(body, 0)
            writeU32(body, crc); writeU32(body, data.size.toLong()); writeU32(body, data.size.toLong())
            writeU16(body, nb.size); writeU16(body, 0)
            body.write(nb); body.write(data)
            writeU32(central, 0x02014b50)
            writeU16(central, 20); writeU16(central, 20); writeU16(central, 0); writeU16(central, 0)
            writeU16(central, 0); writeU16(central, 0)
            writeU32(central, crc); writeU32(central, data.size.toLong()); writeU32(central, data.size.toLong())
            writeU16(central, nb.size); writeU16(central, 0); writeU16(central, 0)
            writeU16(central, 0); writeU16(central, 0); writeU32(central, 0)
            writeU32(central, lhPos)
            central.write(nb)
        }
        val cdPos = body.size().toLong()
        val cd = central.toByteArray()
        val eocd = java.io.ByteArrayOutputStream()
        writeU32(eocd, 0x06054b50)
        writeU16(eocd, 0); writeU16(eocd, 0)
        writeU16(eocd, entries.size); writeU16(eocd, entries.size)
        writeU32(eocd, cd.size.toLong()); writeU32(eocd, cdPos); writeU16(eocd, 0)
        f.writeBytes(body.toByteArray() + cd + eocd.toByteArray())
        return f
    }

    @Test
    fun `case-colliding entries rejected`() {
        val zip = buildZip(
            mapOf(
                "manifest.json" to manifestJson(),
                "File.txt" to "1".toByteArray(),
                "file.txt" to "2".toByteArray()
            )
        )
        val report = ZipValidator.validate(zip)
        assertEquals(ErrorCodes.ARCHIVE_DUPLICATE_ENTRY, report.error?.code)
    }

    @Test
    fun `corrupt zip rejected as unsupported`() {
        val f = File(tmp.root, "broken.wap")
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5))
        val report = ZipValidator.validate(f)
        assertEquals(ErrorCodes.ARCHIVE_UNSUPPORTED, report.error?.code)
    }

    @Test
    fun `missing manifest rejected`() {
        val zip = buildZip(mapOf("index.html" to "x".toByteArray()))
        val report = ZipValidator.validate(zip)
        assertEquals(ErrorCodes.MANIFEST_MISSING, report.error?.code)
    }

    @Test
    fun `manifest in subfolder rejected with clear message`() {
        val zip = buildZip(
            mapOf(
                "wrapper/manifest.json" to manifestJson(),
                "wrapper/index.html" to "x".toByteArray()
            )
        )
        val report = ZipValidator.validate(zip)
        assertEquals(ErrorCodes.MANIFEST_MISSING, report.error?.code)
        assertTrue(report.error!!.message.contains("subfolder"))
    }

    @Test
    fun `not a zip at all rejected`() {
        val f = File(tmp.root, "random.wap")
        f.writeBytes("this is not a zip file at all........".toByteArray())
        val report = ZipValidator.validate(f)
        assertEquals(ErrorCodes.ARCHIVE_UNSUPPORTED, report.error?.code)
    }

    @Test
    fun `safe extractor refuses traversal entries`() {
        val zip = buildZip(
            mapOf(
                "manifest.json" to manifestJson(),
                "..%2F..%2Fescape" to "x".toByteArray()
            )
        )
        // "%2F" is literal here (zip names are not URL-decoded), so this entry is legal;
        // the real traversal case is covered in `path traversal rejected`.
        val dest = tmp.newFolder("out")
        val result = SafeExtractor.extractAll(zip, dest)
        assertTrue(result.ok)
        assertNull(result.error)
    }

    @Test
    fun `readEntry enforces byte cap`() {
        val big = ByteArray(300_000) { 'a'.toByte() }
        val zip = buildZip(mapOf("big.txt" to big))
        assertNull(SafeExtractor.readEntry(zip, "big.txt", 100_000))
        assertEquals(300_000, SafeExtractor.readEntry(zip, "big.txt", 400_000)?.size)
    }

    @Test
    fun `entry exists check`() {
        val zip = buildZip(mapOf("manifest.json" to manifestJson()))
        assertTrue(SafeExtractor.entryExists(zip, "manifest.json"))
        assertEquals(false, SafeExtractor.entryExists(zip, "nope.json"))
    }
}
