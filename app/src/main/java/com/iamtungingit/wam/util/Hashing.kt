package com.iamtungingit.wam.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256File(file: File): String? = try {
        file.inputStream().buffered().use { sha256Stream(it) }
    } catch (e: Exception) {
        null
    }

    fun sha256Stream(input: InputStream): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /** Synthetic per-WAP origin domain (spec D6): w<first 20 hex of SHA-256(id)>.wap.internal */
    fun originDomainFor(wapId: String): String =
        "w" + sha256Hex(wapId).take(20) + ".wap.internal"

    fun originFor(wapId: String): String = "https://" + originDomainFor(wapId)
}
