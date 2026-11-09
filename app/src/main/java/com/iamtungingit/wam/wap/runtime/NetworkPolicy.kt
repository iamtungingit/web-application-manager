package com.iamtungingit.wam.wap.runtime

/**
 * Network policy values and strict resolution (spec §9.5).
 * DENY < ASK < ALLOW. Overrides may only keep or tighten; user choice always wins.
 */
object NetworkPolicy {
    const val ALLOW = "allow"
    const val DENY = "deny"
    const val ASK = "ask"
    const val INHERIT = "inherit"

    fun normalize(v: String?): String? = when (v?.lowercase()?.trim()) {
        ALLOW -> ALLOW
        DENY -> DENY
        ASK -> ASK
        INHERIT -> INHERIT
        else -> null
    }

    fun strictness(v: String): Int = when (v) {
        DENY -> 0
        ASK -> 1
        else -> 2
    }

    /** Per-WAP user preference resolution; "inherit" falls back to the global default. */
    fun resolveForWap(global: String, perWapPref: String?): String {
        val p = normalize(perWapPref)
        return if (p == null || p == INHERIT) global else p
    }

    /** Merges a widget/intent request that can only tighten (spec §9.5.3, §12). */
    fun applyOverride(requested: String?, current: String): String {
        val r = normalize(requested) ?: return current
        if (r == INHERIT) return current
        return if (strictness(r) <= strictness(current)) r else current
    }

    /** Strictest of two policies (used for widget overrides on top of user prefs). */
    fun strictest(a: String, b: String): String =
        if (strictness(a) <= strictness(b)) a else b
}
