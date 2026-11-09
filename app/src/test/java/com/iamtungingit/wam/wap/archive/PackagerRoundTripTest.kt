package com.iamtungingit.wam.wap.archive

import com.iamtungingit.wam.wap.manifest.ManifestParser
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip regression for the on-device "[ARCHIVE_UNSUPPORTED]" packaging report:
 * package a template-like project, then validate + parse it exactly like an import.
 */
class PackagerRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun templateProject(dir: File) {
        File(dir, "manifest.json").writeText(
            """{
  "manifestVersion": 1,
  "id": "hello_test",
  "name": "Hello Test",
  "version": "1.0.0",
  "type": "offline",
  "entry": "index.html",
  "display": { "theme": "system" },
  "widget": { "enabled": true, "label": "Hello", "style": { "shape": "rounded", "showLabel": true } },
  "launch": { "allowedEntries": ["index.html"] },
  "data": { "persistent": true, "bridge": false }
}"""
        )
        File(dir, "index.html").writeText(
            "<!DOCTYPE html>\n<html><head><title>Hi</title></head><body><h1>Hi</h1></body></html>\n"
        )
        File(dir, "style.css").writeText("body { margin: 0; color: #fff; }\n")
        File(dir, "app.js").writeText("console.log('hi');\n")
        val assets = File(dir, "assets")
        assets.mkdirs()
        File(assets, "icon.png").writeBytes(ByteArray(64) { it.toByte() })
        // Excluded categories must not break anything.
        File(dir, ".hidden").writeText("x")
        File(dir, "Thumbs.db").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `blank template project packages and round-trips`() {
        val project = tmp.newFolder("Blank")
        templateProject(project)
        val out = File(tmp.newFolder("out"), "Blank.wap")

        val r = WapPackager.packageProject(project, out, Long.MAX_VALUE / 4)
        assertTrue("packaging failed: ${r.error}", r.ok)
        assertTrue(out.isFile && out.length() > 0)

        val report = ZipValidator.validate(out)
        assertTrue("validate failed: ${report.error}", report.ok)
        assertTrue("manifest.json" in report.entryNames)

        val bytes = SafeExtractor.readEntry(out, "manifest.json", ManifestParser.MAX_MANIFEST_BYTES)!!
        val mf = ManifestParser.fromBytes(bytes, report.entryNames, Long.MAX_VALUE / 4)
        assertTrue("manifest failed: ${(mf as? ManifestParser.Result.Failure)?.error}", mf is ManifestParser.Result.Success)
    }

    @Test
    fun `empty nested folders and unicode file names survive`() {
        val project = tmp.newFolder("Uni")
        File(project, "manifest.json").writeText(
            """{"id":"uni","name":"Uni","version":"1.0.0","type":"offline","entry":"index.html"}"""
        )
        File(project, "index.html").writeText("<html></html>")
        File(project, "caf\u00e9 notes.txt").writeText("ok")
        val deep = File(project, "a/b/c")
        deep.mkdirs()
        File(deep, "x.js").writeText("// x\n")
        val out = File(tmp.newFolder("out"), "uni.wap")
        val r = WapPackager.packageProject(project, out, Long.MAX_VALUE / 4)
        assertTrue("packaging failed: ${r.error}", r.ok)
    }

    @Test
    fun `missing manifest gives typed error not crash`() {
        val project = tmp.newFolder("NoMf")
        File(project, "index.html").writeText("<html></html>")
        val out = File(tmp.newFolder("out"), "x.wap")
        val r = WapPackager.packageProject(project, out, Long.MAX_VALUE / 4)
        assertTrue(!r.ok)
        assertTrue(r.error!!.code != com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED)
    }

    @Test
    fun `entry pointing to missing file gives typed error`() {
        val project = tmp.newFolder("BadEntry")
        File(project, "manifest.json").writeText(
            """{"id":"bad","name":"Bad","version":"1.0.0","type":"offline","entry":"missing.html"}"""
        )
        val out = File(tmp.newFolder("out"), "x.wap")
        val r = WapPackager.packageProject(project, out, Long.MAX_VALUE / 4)
        assertTrue(!r.ok)
        assertTrue(r.error!!.code == com.iamtungingit.wam.wap.manifest.ErrorCodes.ENTRY_NOT_FOUND)
    }
}
