package com.iamtungingit.wam.wap.runtime

import com.iamtungingit.wam.util.MimeTypes
import com.iamtungingit.wam.util.PathSanitizer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal local HTTP server that lets WAM run projects in the device's REAL
 * browser (Chrome / Chromium / Firefox) instead of the in-app WebView.
 *
 * Why this exists (user report): on the target device the in-app WebView painted
 * a solid black screen for every page — including plain https://example.com —
 * through three rounds of hardening. The user then asked to "use Chromium or
 * Firefox instead of WebView". The system browser IS Chromium (Chrome) or
 * Gecko (Firefox), so the cleanest replacement is to serve the project's
 * extracted files over http://127.0.0.1:<random port> and hand the URL to
 * whatever browser the user prefers. A real browser cannot show WAM's black
 * screen, and the user keeps their own ad-blocking / dark-mode / desktop-mode
 * browser features.
 *
 * Security (spec §3.5, §9 spirit):
 *  - binds to 127.0.0.1 ONLY — never reachable from the network;
 *  - every URL must carry an unguessable per-session token (/p/<token>/…),
 *    so other local apps cannot probe the content;
 *  - paths are re-sanitized and canonically contained inside the project root;
 *  - HTML responses carry the same Content-Security-Policy as the WebView
 *    runtime, so a WAP whose network was denied stays offline in the browser too;
 *  - GET/HEAD only, request header block capped, per-socket read timeout.
 *
 * Zero external dependencies: raw ServerSocket + threads, so the on-device
 * Gradle build (Android Code Studio) needs no new libraries.
 */
class LocalPreviewServer(
    private val root: File,
    private val networkAllowed: Boolean,
    private val allowedHosts: List<String>,
    private val log: (String) -> Unit = {}
) {

    private val token = newToken()
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "WAM-preview" }
    }
    private val running = AtomicBoolean(false)
    @Volatile var port: Int = 0
        private set

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            ss.soTimeout = 0 // accept() blocks; worker sockets get their own timeout
            server = ss
            port = ss.localPort
            running.set(true)
            Thread({
                while (running.get()) {
                    val s = try {
                        ss.accept()
                    } catch (t: Throwable) {
                        if (running.get()) log("server accept failed: $t")
                        break
                    }
                    pool.execute { handle(s) }
                }
            }, "WAM-preview-accept").apply { isDaemon = true }.start()
            log("preview server on 127.0.0.1:$port (token ${token.take(6)}…)")
            true
        } catch (t: Throwable) {
            log("preview server failed to start: ${t.message ?: t.javaClass.simpleName}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    val isRunning: Boolean get() = running.get()

    /** Public URL for [pathWithQuery], e.g. "index.html?a=1" (path is percent-encoded). */
    fun urlFor(pathWithQuery: String): String {
        val path = pathWithQuery.substringBefore('?').substringBefore('#')
        val qf = pathWithQuery.substring(path.length)
        val encoded = path.split('/').joinToString("/") { seg -> encodeSegment(seg) }
        return "http://127.0.0.1:$port/p/$token/$encoded$qf"
    }

    // ------------------------------------------------------------- connection

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = SOCKET_TIMEOUT_MS
            sock.getInputStream().use { ins ->
                BufferedOutputStream(sock.getOutputStream()).use { out ->
                    val requestLine = readLine(ins, MAX_LINE) ?: return
                    if (requestLine.length > MAX_LINE) return
                    val parts = requestLine.split(' ')
                    if (parts.size < 3) { respondError(out, 400, "Bad request"); return }
                    val method = parts[0].uppercase()
                    if (method != "GET" && method != "HEAD") {
                        respondError(out, 405, "Method not allowed"); return
                    }
                    // Drain the (capped) header block; we do not need its fields.
                    var headerBytes = 0
                    while (true) {
                        val line = readLine(ins, MAX_LINE) ?: break
                        headerBytes += line.length
                        if (line.isEmpty() || headerBytes > MAX_HEADERS) break
                    }
                    serveFile(out, method, parts[1])
                }
            }
        } catch (_: SocketTimeoutException) {
            // idle browser connection — harmless
        } catch (_: Exception) {
            // client hung up mid-request — harmless
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun serveFile(out: BufferedOutputStream, method: String, rawTarget: String) {
        val target = rawTarget.substringBefore('#')
        val querySep = target.indexOf('?')
        val rawPath = if (querySep >= 0) target.substring(0, querySep) else target

        // Token gate: /p/<token>/<path…>
        val segs = rawPath.trimStart('/').split('/')
        if (segs.size < 3 || segs[0] != "p" || segs[1] != token) {
            respondError(out, 403, "Forbidden"); return
        }
        val relRaw = segs.drop(2).joinToString("/")
        if (relRaw.isBlank()) { respondError(out, 404, "Not found"); return }

        val decoded = try { decodePercent(relRaw) } catch (e: Exception) {
            respondError(out, 400, "Bad path"); return
        }
        val norm = PathSanitizer.sanitizeRelativePath(decoded) ?: run {
            respondError(out, 403, "Forbidden"); return
        }
        val f = try {
            File(root, norm).canonicalFile
        } catch (e: Exception) {
            respondError(out, 404, "Not found"); return
        }
        if (!f.path.startsWith(root.canonicalFile.path + File.separator) || !f.isFile) {
            respondError(out, 404, "Not found"); return
        }

        val mime = MimeTypes.fromPath(f.name)
        val headers = StringBuilder()
        headers.append("HTTP/1.1 200 OK\r\n")
        headers.append("Content-Type: ").append(mime)
        if (MimeTypes.isText(mime)) headers.append("; charset=utf-8")
        headers.append("\r\n")
        headers.append("X-Content-Type-Options: nosniff\r\n")
        headers.append("Cache-Control: no-store\r\n")
        headers.append("Referrer-Policy: no-referrer\r\n")
        headers.append("Connection: close\r\n")
        if (mime == "text/html") {
            headers.append("Content-Security-Policy: ").append(buildCsp()).append("\r\n")
        }

        // Range requests are answered with a plain 200 full body — browsers accept
        // that for preview-sized media and it keeps the server tiny.
        headers.append("Accept-Ranges: none\r\n")
        headers.append("Content-Length: ").append(f.length()).append("\r\n\r\n")
        writeHeaders(out, headers)
        if (method == "GET") copyStream(FileInputStream(f), out)
    }

    private fun buildCsp(): String {
        return if (!networkAllowed) {
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self' data:; " +
                "media-src 'self' data: blob:; connect-src 'self'; " +
                "object-src 'none'; frame-ancestors 'none'"
        } else {
            val extra = allowedHosts.joinToString(" ") { "https://$it" }
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob: $extra; font-src 'self' data: $extra; " +
                "media-src 'self' data: blob: $extra; connect-src 'self' $extra; " +
                "object-src 'none'; frame-ancestors 'none'"
        }
    }

    // ------------------------------------------------------------- io helpers

    private fun writeHeaders(out: BufferedOutputStream, headers: StringBuilder) {
        out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copyStream(input: InputStream, out: BufferedOutputStream) {
        input.use { ins -> ins.copyTo(out, 65536) }
        out.flush()
    }

    private fun respondError(out: BufferedOutputStream, code: Int, reason: String) {
        val body = "<!doctype html><meta charset=\"utf-8\">" +
            "<title>$code</title><body style=\"font-family:sans-serif;padding:2rem\">" +
            "<h3>$code ${reason}</h3><p>WAM local preview server</p>"
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Reads a CRLF/LF-terminated line with a hard cap (slowloris-safe). */
    private fun readLine(ins: InputStream, cap: Int): String? {
        val sb = StringBuilder(96)
        while (sb.length < cap) {
            val b = ins.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /**
     * Decodes %XX sequences as UTF-8 (multibyte-safe). '+' must stay literal in a
     * URL path, so it is escaped before URLDecoder sees it.
     */
    private fun decodePercent(s: String): String =
        URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun encodeSegment(seg: String): String {
        val sb = StringBuilder()
        for (b in seg.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~' || c == '!'
            ) sb.append(c) else sb.append('%').append(String.format("%02X", b))
        }
        return sb.toString()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 32 * 1024
    }
}
