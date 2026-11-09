package com.iamtungingit.wam.ui.settings

import com.iamtungingit.wam.wap.install.VersionComparer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub releases for a newer WAM version (v1.5.0 user request #6:
 * "Check for update — when you click it sees if the GitHub release page has a new
 * release and tells you it has and sends you there").
 *
 * Pure JVM (HttpsURLConnection + org.json), no new dependencies — safe for the
 * on-device build. All failures degrade to a friendly message.
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/iamtungingit/web-application-manager"
    private const val API_LATEST =
        "https://api.github.com/repos/iamtungingit/web-application-manager/releases/latest"

    data class CheckResult(
        val ok: Boolean,
        val currentVersion: String,
        val latestVersion: String?,
        val updateAvailable: Boolean,
        val error: String? = null
    )

    /**
     * Blocking network call — must run on a background thread (callers use Ui.runIO).
     */
    fun check(currentVersion: String): CheckResult {
        return try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "WebApplicationManager/$currentVersion")
            try {
                val code = conn.responseCode
                if (code != 200) {
                    return CheckResult(false, currentVersion, null, false, "HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(body).optString("tag_name")
                if (tag.isEmpty()) {
                    return CheckResult(false, currentVersion, null, false, "no tag_name in release")
                }
                val latest = tag.removePrefix("v").removePrefix("V")
                val cmp = VersionComparer.compare(currentVersion, null, latest, null)
                CheckResult(true, currentVersion, latest, cmp < 0)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CheckResult(false, currentVersion, null, false, e.message ?: "network error")
        }
    }
}
