package com.iamtungingit.wam.wap.bridge

import com.iamtungingit.wam.wap.archive.SizeLimits
import org.json.JSONObject

/**
 * Bridge wire protocol: JSON request {id, method, args} → {id, ok, result|error} (spec §9.6).
 */
object BridgeProtocol {

    const val BRIDGE_NAME = "WAPBridge"
    const val MAX_MESSAGE = SizeLimits.BRIDGE_MAX_MESSAGE

    data class Request(val id: Long, val method: String, val args: JSONObject)

    fun parse(text: String): Request? = try {
        val o = JSONObject(text)
        val id = o.optLong("id", -1L)
        val method = o.optString("method", "")
        if (id < 0 || method.isEmpty()) null
        else Request(id, method, o.optJSONObject("args") ?: JSONObject())
    } catch (e: Exception) {
        null
    }

    fun okJson(id: Long, result: Any?): String = try {
        JSONObject().put("id", id).put("ok", true).put("result", result ?: JSONObject.NULL).toString()
    } catch (e: Exception) {
        errJson(id, "serialize_failed")
    }

    fun errJson(id: Long, error: String): String = try {
        JSONObject().put("id", id).put("ok", false).put("error", error).toString()
    } catch (e: Exception) {
        "{\"id\":$id,\"ok\":false,\"error\":\"serialize_failed\"}"
    }

    fun stringArg(args: JSONObject, key: String): String? =
        if (args.has(key) && !args.isNull(key) && args.opt(key) is String) args.getString(key) else null

    /** Simple fixed-window per-session rate limiter (spec §9.6). */
    class RateLimiter(private val maxPerSecond: Int) {
        private var windowStart = 0L
        private var count = 0

        @Synchronized
        fun allow(): Boolean {
            val now = System.currentTimeMillis() / 1000L
            if (now != windowStart) {
                windowStart = now
                count = 0
            }
            if (count >= maxPerSecond) return false
            count++
            return true
        }
    }
}
