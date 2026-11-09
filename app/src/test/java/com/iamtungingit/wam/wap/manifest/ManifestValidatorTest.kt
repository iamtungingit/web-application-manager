package com.iamtungingit.wam.wap.manifest

import com.iamtungingit.wam.util.PathSanitizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {

    private val entryNames = setOf("index.html", "app.js", "style.css", "assets/icon.png")

    private fun minimal(): JSONObject = JSONObject().apply {
        put("id", "calculator")
        put("name", "Calculator")
        put("version", "1.0.0")
        put("type", "offline")
        put("entry", "index.html")
    }

    private fun validate(json: JSONObject, entries: Set<String>? = entryNames) =
        ManifestValidator.validate(json, entries, 100L * 1024 * 1024)

    @Test
    fun `minimal valid manifest passes with derived versionCode`() {
        val r = validate(minimal())
        assertTrue(r is ManifestParser.Result.Success)
        r as ManifestParser.Result.Success
        assertEquals(1_000_000L, r.manifest.versionCode)
        assertEquals("index.html", r.manifest.entry)
    }

    @Test
    fun `online example from the spec is accepted`() {
        val json = JSONObject().apply {
            put("name", "Online Calculator")
            put("id", "calculator")
            put("version", "1.0.0")
            put("type", "online")
            put("entry", "https://example.com/calculator")
            put("network", JSONObject().put("required", true))
        }
        val r = validate(json, emptySet())
        assertTrue(r is ManifestParser.Result.Success)
    }

    @Test
    fun `required fields are enforced`() {
        val m = minimal(); m.remove("id")
        assertTrue(validate(m) is ManifestParser.Result.Failure)
        val n = minimal(); n.remove("name")
        assertTrue(validate(n) is ManifestParser.Result.Failure)
        val v = minimal(); v.remove("version")
        assertTrue(validate(v) is ManifestParser.Result.Failure)
        val t = minimal(); t.remove("type")
        assertTrue(validate(t) is ManifestParser.Result.Failure)
        val e = minimal(); e.remove("entry")
        assertTrue(validate(e) is ManifestParser.Result.Failure)
    }

    @Test
    fun `error codes are correct`() {
        val id = minimal(); id.put("id", "Bad ID")
        assertEquals(ErrorCodes.ID_INVALID, (validate(id) as ManifestParser.Result.Failure).error.code)

        val ver = minimal(); ver.put("version", "1.0")
        assertEquals(ErrorCodes.VERSION_INVALID, (validate(ver) as ManifestParser.Result.Failure).error.code)

        val typ = minimal(); typ.put("type", "hybrid")
        assertEquals(ErrorCodes.TYPE_INVALID, (validate(typ) as ManifestParser.Result.Failure).error.code)

        val entry = minimal(); entry.put("entry", "missing.html")
        assertEquals(ErrorCodes.ENTRY_NOT_FOUND, (validate(entry) as ManifestParser.Result.Failure).error.code)

        val scheme = minimal(); scheme.put("type", "online"); scheme.put("entry", "ftp://x.com")
        assertEquals(ErrorCodes.URL_SCHEME_FORBIDDEN, (validate(scheme) as ManifestParser.Result.Failure).error.code)

        val userinfo = minimal(); userinfo.put("type", "online")
        userinfo.put("entry", "https://user:pass@example.com/")
        assertEquals(ErrorCodes.URL_USERINFO_FORBIDDEN, (validate(userinfo) as ManifestParser.Result.Failure).error.code)

        val runtime = minimal()
        runtime.put("runtime", JSONObject().put("minVersion", 99))
        assertEquals(ErrorCodes.RUNTIME_TOO_OLD, (validate(runtime) as ManifestParser.Result.Failure).error.code)

        val icon = minimal(); icon.put("icon", "assets/icon.png")
        assertTrue(validate(icon) is ManifestParser.Result.Success)
        val badIcon = minimal(); badIcon.put("icon", "assets/logo.svg")
        assertEquals(ErrorCodes.ICON_INVALID, (validate(badIcon) as ManifestParser.Result.Failure).error.code)
    }

    @Test
    fun `unknown fields produce warnings`() {
        val m = minimal(); m.put("futureField", 42)
        val r = validate(m) as ManifestParser.Result.Success
        assertTrue(r.warnings.any { it.contains("futureField") })
    }

    @Test
    fun `quota is capped to the global maximum`() {
        val m = minimal()
        m.put("data", JSONObject().put("quotaBytes", 999L * 1024 * 1024))
        val r = validate(m) as ManifestParser.Result.Success
        assertEquals(100L * 1024 * 1024, r.manifest.quotaBytes)
        assertTrue(r.warnings.any { it.contains("capped") })
    }

    @Test
    fun `network required never grants by itself`() {
        val m = minimal()
        m.put("network", JSONObject().put("required", true))
        val r = validate(m) as ManifestParser.Result.Success
        assertTrue(r.manifest.effectiveNetworkRequired)
        // informational only — no host access implied
        assertEquals(emptyList<String>(), r.manifest.allowedHosts)
    }

    @Test
    fun `ip literal hosts rejected`() {
        val m = minimal()
        m.put("network", JSONObject().put("allowedHosts", org.json.JSONArray().put("192.168.1.1")))
        assertTrue(validate(m) is ManifestParser.Result.Failure)
    }

    @Test
    fun `wildcard hosts accepted`() {
        val m = minimal()
        m.put("network", JSONObject().put("allowedHosts", org.json.JSONArray().put("*.example.com")))
        val r = validate(m) as ManifestParser.Result.Success
        assertTrue(UrlValidator.hostAllowed("api.example.com", r.manifest.allowedHosts))
        assertTrue(!UrlValidator.hostAllowed("evil.com", r.manifest.allowedHosts))
    }
}
