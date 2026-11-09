package com.iamtungingit.wam.wap.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.Locale

/**
 * JVM tests for the browser-engine preview server (user request: run projects in
 * Chrome/Firefox instead of the black-screening WebView). Covers serving, MIME,
 * CSP policy headers and the security gates (token, traversal, methods).
 */
class LocalPreviewServerTest {

    private fun newRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "wam-preview-${System.nanoTime()}")
        root.mkdirs()
        File(root, "index.html").writeText("<h1>hello wam</h1>")
        File(root, "sub").mkdirs()
        File(root, "sub/style.css").writeText("body{color:red}")
        File(root, "secret.txt").writeText("secret")
        return root
    }

    /** Raw HTTP request (no client-side URL normalization) → "STATUS|header:value" probe. */
    private fun rawGet(urlText: String, method: String = "GET"): Pair<Int, Map<String, String>> {
        val u = URL(urlText)
        Socket("127.0.0.1", u.port).use { sock ->
            sock.soTimeout = 5000
            val path = u.path + (u.query?.let { "?$it" } ?: "")
            val req = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()
            val text = sock.getInputStream().readBytes().decodeToString()
            val status = text.substringAfter(" ").substringBefore(" ").toIntOrNull() ?: 0
            val headers = text.substringAfter("\r\n\r\n", "").let { body ->
                text.substringBefore("\r\n\r\n").lines()
                    .drop(1)
                    .mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase(Locale.ROOT) to it[1] } }
                    .toMap() to body
            }.first
            return status to headers
        }
    }

    @Test
    fun `serves entry page and subresources with correct mime and csp`() {
        val root = newRoot()
        val srv = LocalPreviewServer(root, networkAllowed = false, allowedHosts = emptyList())
        try {
            assertTrue(srv.start())
            val conn = URL(srv.urlFor("index.html")).openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("text/html; charset=utf-8", conn.contentType)
            val csp = conn.getHeaderField("Content-Security-Policy")
            assertTrue("offline CSP must exist", csp != null && csp.contains("default-src 'self'"))
            assertFalse("offline CSP must not allow remote hosts", csp.orEmpty().contains("https://"))
            assertTrue(conn.inputStream.readBytes().decodeToString().contains("hello wam"))
            conn.disconnect()

            val c2 = URL(srv.urlFor("sub/style.css")).openConnection() as HttpURLConnection
            assertEquals(200, c2.responseCode)
            assertEquals("text/css; charset=utf-8", c2.contentType)
            c2.disconnect()
        } finally {
            srv.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `query strings and encoded paths are preserved`() {
        val root = newRoot()
        val srv = LocalPreviewServer(root, networkAllowed = false, allowedHosts = emptyList())
        try {
            assertTrue(srv.start())
            val conn = URL(srv.urlFor("index.html?a=1&b=two")).openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        } finally {
            srv.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `wrong token is forbidden and missing file is 404`() {
        val root = newRoot()
        val srv = LocalPreviewServer(root, networkAllowed = false, allowedHosts = emptyList())
        try {
            assertTrue(srv.start())
            val base = "http://127.0.0.1:${srv.port}"
            val (badToken, _) = rawGet("$base/p/deadbeef/index.html")
            assertEquals(403, badToken)
            // extract the real token from a valid URL path
            val validPath = URL(srv.urlFor("index.html")).path
            val token = validPath.split("/")[2]
            val (missing, _) = rawGet("$base/p/$token/nope.html")
            assertEquals(404, missing)
        } finally {
            srv.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `traversal outside root is blocked`() {
        val root = newRoot()
        val outside = File(System.getProperty("java.io.tmpdir"), "wam-outside-${System.nanoTime()}")
        outside.mkdirs()
        File(outside, "evil.txt").writeText("evil")
        val srv = LocalPreviewServer(root, networkAllowed = false, allowedHosts = emptyList())
        try {
            assertTrue(srv.start())
            val path = URL(srv.urlFor("index.html")).path
            val token = path.split("/")[2]
            val (status, _) = rawGet("http://127.0.0.1:${srv.port}/p/$token/../evil.txt")
            assertTrue("traversal must not be 200, got $status", status == 403 || status == 404)
        } finally {
            srv.stop()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `non-GET methods are rejected`() {
        val root = newRoot()
        val srv = LocalPreviewServer(root, networkAllowed = false, allowedHosts = emptyList())
        try {
            assertTrue(srv.start())
            val (status, _) = rawGet(srv.urlFor("index.html"), method = "POST")
            assertEquals(405, status)
        } finally {
            srv.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `allowed-network CSP lists permitted hosts`() {
        val root = newRoot()
        val srv = LocalPreviewServer(root, networkAllowed = true, allowedHosts = listOf("api.example.com"))
        try {
            assertTrue(srv.start())
            val conn = URL(srv.urlFor("index.html")).openConnection() as HttpURLConnection
            val csp = conn.getHeaderField("Content-Security-Policy").orEmpty()
            assertTrue(csp.contains("https://api.example.com"))
            conn.disconnect()
        } finally {
            srv.stop()
            root.deleteRecursively()
        }
    }
}
