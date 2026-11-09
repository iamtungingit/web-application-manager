package com.iamtungingit.wam.wap.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparerTest {

    @Test
    fun `semver numeric compare`() {
        assertTrue(VersionComparer.compare("1.0.0", null, "1.0.1", null) < 0)
        assertTrue(VersionComparer.compare("2.0.0", null, "1.9.9", null) > 0)
        assertEquals(0, VersionComparer.compare("1.2.3", null, "1.2.3", null))
    }

    @Test
    fun `versionCode takes precedence`() {
        assertTrue(VersionComparer.compare("2.0.0", 5L, "1.0.0", 6L) < 0)
        assertEquals(0, VersionComparer.compare("1.0.0", 7L, "9.9.9", 7L))
    }

    @Test
    fun `prerelease sorts before release`() {
        assertTrue(VersionComparer.compare("1.0.0-rc.1", null, "1.0.0", null) < 0)
    }

    @Test
    fun `build metadata ignored for ordering`() {
        assertEquals(0, VersionComparer.compare("1.0.0+build.1", null, "1.0.0", null))
    }

    @Test
    fun `derived versionCode`() {
        assertEquals(1_002_003L, VersionComparer.deriveVersionCode("1.2.3"))
        assertEquals(10_000_000L, VersionComparer.deriveVersionCode("10.0.0"))
        assertEquals(0L, VersionComparer.deriveVersionCode("0.0.0"))
    }
}
