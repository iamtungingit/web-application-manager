package com.iamtungingit.wam.wap.install

/**
 * Version ordering: versionCode first when present in both, else semver compare
 * where a prerelease sorts before the plain release (spec §5.3, §7.2.3).
 * Pure JVM, unit tested.
 */
object VersionComparer {

    private val regex = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)([-+][0-9A-Za-z.-]+)?$")

    fun compare(aVersion: String?, aCode: Long?, bVersion: String?, bCode: Long?): Int {
        if (aCode != null && bCode != null) return aCode.compareTo(bCode)
        val pa = aVersion?.let(::parseCore) ?: return 0
        val pb = bVersion?.let(::parseCore) ?: return 0
        for (i in 0..2) {
            val c = pa.nums[i].compareTo(pb.nums[i])
            if (c != 0) return c
        }
        return when {
            pa.prerelease == null && pb.prerelease == null -> 0
            pa.prerelease == null -> 1
            pb.prerelease == null -> -1
            else -> pa.prerelease.compareTo(pb.prerelease)
        }
    }

    fun deriveVersionCode(version: String): Long {
        val core = version.split('-', '+').first()
        val parts = core.split('.')
        val major = parts.getOrNull(0)?.toLongOrNull()?.coerceIn(0, 9999) ?: 0
        val minor = parts.getOrNull(1)?.toLongOrNull()?.coerceIn(0, 999) ?: 0
        val patch = parts.getOrNull(2)?.toLongOrNull()?.coerceIn(0, 999) ?: 0
        return major * 1_000_000L + minor * 1_000L + patch
    }

    private data class Parsed(val nums: List<Int>, val prerelease: String?)

    private fun parseCore(v: String): Parsed? {
        val m = regex.find(v) ?: return null
        val nums = listOf(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt()
        )
        // group 4 carries the leading '-' (prerelease) or '+' (build metadata).
        // Semver: build metadata is ignored for precedence; prerelease sorts before release.
        val suffix = m.groupValues[4].ifEmpty { null } ?: return Parsed(nums, null)
        val prerelease = if (suffix.startsWith("-")) suffix.drop(1) else null
        return Parsed(nums, prerelease)
    }
}
