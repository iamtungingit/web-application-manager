package com.iamtungingit.wam.util

import org.json.JSONArray
import org.json.JSONObject

/** Small org.json helpers + color parsing without android.graphics (pure JVM). */
object JsonUtil {

    fun optString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> null
        }
    }

    fun optBoolean(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Boolean -> v
            is String -> v.lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }

    fun optInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }

    fun optLong(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    fun optObject(o: JSONObject, key: String): JSONObject? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key) as? JSONObject
    }

    fun optArray(o: JSONObject, key: String): JSONArray? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key) as? JSONArray
    }

    fun stringArray(o: JSONObject, key: String): List<String> {
        val arr = optArray(o, key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) out.add(v)
        }
        return out
    }

    /** Parses #RRGGBB or #AARRGGBB into an ARGB int, or null. */
    fun parseColorHex(value: String): Int? {
        val s = value.removePrefix("#").lowercase()
        return when (s.length) {
            6 -> try {
                (0xFF shl 24) or s.toLong(16).toInt()
            } catch (e: NumberFormatException) {
                null
            }
            8 -> try {
                s.toLong(16).toInt()
            } catch (e: NumberFormatException) {
                null
            }
            else -> null
        }
    }

    fun colorToHex(argb: Int): String = "#%08X".format(argb)
}
