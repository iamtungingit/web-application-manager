package com.iamtungingit.wam.wap.manifest

import java.net.URI
import java.net.URISyntaxException

/**
 * Online entry URL validation (spec §5.4). Pure JVM (java.net.URI), unit tested.
 */
object UrlValidator {

    sealed class Result {
        data class Ok(val uri: URI) : Result()
        data class Bad(val code: String, val message: String) : Result()
    }

    private val allowedCleartextHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun validateOnlineEntry(raw: String, allowCleartextLocalhost: Boolean = false): Result {
        if (raw.isEmpty() || raw.length > 2048) {
            return Result.Bad(ErrorCodes.URL_TOO_LONG, "URL length must be 1..2048")
        }
        val uri = try {
            URI(raw.trim())
        } catch (e: URISyntaxException) {
            return Result.Bad(ErrorCodes.URL_SCHEME_FORBIDDEN, "URL is not parseable")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme == null || host == null || host.isEmpty()) {
            return Result.Bad(ErrorCodes.URL_SCHEME_FORBIDDEN, "absolute https URL required")
        }
        when (scheme) {
            "https" -> { /* preferred */ }
            "http" -> {
                val cleartextOk = allowCleartextLocalhost && host in allowedCleartextHosts
                if (!cleartextOk) {
                    return Result.Bad(
                        ErrorCodes.URL_SCHEME_FORBIDDEN,
                        "cleartext http is not allowed (https required)"
                    )
                }
            }
            else -> return Result.Bad(
                ErrorCodes.URL_SCHEME_FORBIDDEN,
                "scheme '$scheme' is forbidden"
            )
        }
        if (uri.userInfo != null) {
            return Result.Bad(ErrorCodes.URL_USERINFO_FORBIDDEN, "userinfo in URL is forbidden")
        }
        return Result.Ok(uri)
    }

    /** True when the host matches an allowedHosts entry ("*" prefix wildcards allowed). */
    fun hostAllowed(host: String, allowedHosts: List<String>): Boolean {
        val h = host.lowercase()
        for (pattern in allowedHosts) {
            val p = pattern.lowercase()
            if (p == h) return true
            if (p.startsWith("*.")) {
                val suffix = p.substring(1) // ".example.com"
                if (h.endsWith(suffix) && h.length > suffix.length) return true
            }
        }
        return false
    }
}
