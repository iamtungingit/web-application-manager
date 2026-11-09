package com.iamtungingit.wam.wap.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentOverrideResolverTest {

    private val allowed = setOf("index.html", "scientific.html")
    private fun resolve(
        id: String? = "calculator",
        entry: String? = null,
        mode: String? = null,
        fullscreen: Any? = null,
        theme: String? = null,
        network: String? = null,
        widgetId: Int? = null,
        widgetWapId: String? = null,
        isOffline: Boolean = true
    ) = IntentOverrideResolver.resolve(
        id, entry, mode, fullscreen, theme, network, widgetId, widgetWapId, isOffline
    ) { it in allowed }

    @Test
    fun `valid id accepted`() {
        val r = resolve()
        assertTrue(r.idValid)
        assertEquals("calculator", r.id)
    }

    @Test
    fun `invalid id flagged`() {
        val r = resolve(id = "Evil App/../../x")
        assertFalse(r.idValid)
    }

    @Test
    fun `missing id is fatal upstream`() {
        val r = resolve(id = null)
        assertFalse(r.idValid)
        assertNull(r.id)
    }

    @Test
    fun `allowed entry override kept`() {
        val r = resolve(entry = "scientific.html")
        assertEquals("scientific.html", r.entry)
    }

    @Test
    fun `disallowed entry dropped`() {
        val r = resolve(entry = "secret.html")
        assertNull(r.entry)
        assertTrue(r.dropped.any { it.contains("not allowed") })
    }

    @Test
    fun `traversal entry dropped`() {
        val r = resolve(entry = "../evil.html")
        assertNull(r.entry)
    }

    @Test
    fun `entry with query and fragment kept`() {
        val r = resolve(entry = "index.html?x=1#top")
        assertEquals("index.html?x=1#top", r.entry)
    }

    @Test
    fun `oversized query dropped`() {
        val r = resolve(entry = "index.html?" + "a".repeat(300))
        assertNull(r.entry)
    }

    @Test
    fun `mode mismatch rejected`() {
        val r = resolve(mode = "online")
        assertNull(r.mode)
        val ok = resolve(mode = "offline")
        assertEquals("offline", ok.mode)
    }

    @Test
    fun `fullscreen must be Boolean`() {
        val r = resolve(fullscreen = true)
        assertEquals(true, r.fullscreen)
        val bad = resolve(fullscreen = "true")
        assertNull(bad.fullscreen)
    }

    @Test
    fun `theme whitelist`() {
        assertEquals("dark", resolve(theme = "dark").theme)
        assertNull(resolve(theme = "amoled").theme)
    }

    @Test
    fun `network normalization and relaxation attempts`() {
        assertEquals("deny", resolve(network = "deny").networkRequest)
        assertEquals("ask", resolve(network = "ask").networkRequest)
        assertEquals("allow", resolve(network = "ALLOW").networkRequest)
        assertNull(resolve(network = "always").networkRequest)
        assertNull(resolve(network = "inherit").networkRequest)
        // Relaxation is enforced later by NetworkPolicy.applyOverride —
        // an "allow" override must never relax an "ask"/"deny" policy:
        assertEquals("deny", NetworkPolicy.applyOverride("allow", "deny"))
        assertEquals("ask", NetworkPolicy.applyOverride("allow", "ask"))
    }

    @Test
    fun `widget id must match the wap`() {
        val r = resolve(widgetId = 42, widgetWapId = "other")
        assertFalse(r.widgetUsable)
        val ok = resolve(widgetId = 42, widgetWapId = "calculator")
        assertTrue(ok.widgetUsable)
        assertEquals(42, ok.widgetId)
    }
}

class NetworkPolicyTest {

    @Test
    fun `per wap pref overrides global`() {
        assertEquals("allow", NetworkPolicy.resolveForWap("ask", "allow"))
        assertEquals("ask", NetworkPolicy.resolveForWap("allow", "ask"))
        assertEquals("global-fallback", "deny", NetworkPolicy.resolveForWap("deny", "inherit"))
        assertEquals("deny", NetworkPolicy.resolveForWap("deny", null))
    }

    @Test
    fun `strictness ordering`() {
        assertTrue(NetworkPolicy.strictness("deny") < NetworkPolicy.strictness("ask"))
        assertTrue(NetworkPolicy.strictness("ask") < NetworkPolicy.strictness("allow"))
    }

    @Test
    fun `override can only tighten`() {
        assertEquals("deny", NetworkPolicy.applyOverride("deny", "allow"))
        assertEquals("deny", NetworkPolicy.applyOverride("allow", "deny"))
        assertEquals("ask", NetworkPolicy.applyOverride("ask", "ask"))
        assertEquals("keep", "allow", NetworkPolicy.applyOverride(null, "allow"))
    }

    @Test
    fun `strictest of two`() {
        assertEquals("deny", NetworkPolicy.strictest("deny", "allow"))
        assertEquals("ask", NetworkPolicy.strictest("ask", "allow"))
    }
}
