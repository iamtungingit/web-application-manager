package com.iamtungingit.wam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSanitizerTest {

    @Test
    fun `valid ids pass`() {
        assertTrue(PathSanitizer.isValidId("calculator"))
        assertTrue(PathSanitizer.isValidId("my-app_2"))
        assertTrue(PathSanitizer.isValidId("com.example.notes"))
    }

    @Test
    fun `invalid ids rejected`() {
        assertFalse(PathSanitizer.isValidId("Calculator"))
        assertFalse(PathSanitizer.isValidId("2app"))
        assertFalse(PathSanitizer.isValidId("../evil"))
        assertFalse(PathSanitizer.isValidId("a".repeat(65)))
        assertFalse(PathSanitizer.isValidId("has space"))
        assertFalse(PathSanitizer.isValidId(""))
        assertFalse(PathSanitizer.isValidId("a..b"))
        assertFalse(PathSanitizer.isValidId("-start"))
    }

    @Test
    fun `traversal paths rejected`() {
        assertNull(PathSanitizer.sanitizeRelativePath("../evil.txt"))
        assertNull(PathSanitizer.sanitizeRelativePath("a/../../evil"))
        assertNull(PathSanitizer.sanitizeRelativePath("/abs/path"))
        assertNull(PathSanitizer.sanitizeRelativePath("C:\\x"))
        assertNull(PathSanitizer.sanitizeRelativePath("back\\slash"))
        assertNull(PathSanitizer.sanitizeRelativePath("nul\u0000byte"))
        assertNull(PathSanitizer.sanitizeRelativePath("a//b"))
        assertNull(PathSanitizer.sanitizeRelativePath("./x"))
        assertNull(PathSanitizer.sanitizeRelativePath("seg/".repeat(40)))
    }

    @Test
    fun `normal paths accepted`() {
        assertEquals("index.html", PathSanitizer.sanitizeRelativePath("index.html"))
        assertEquals("assets/img/logo.png", PathSanitizer.sanitizeRelativePath("assets/img/logo.png"))
    }

    @Test
    fun `project names`() {
        // Spaces are no longer allowed in NEW project names (user-requested rule:
        // "app can only be named with dashes or underscores", warned live while typing).
        assertEquals("My_Calculator", PathSanitizer.sanitizeProjectName("My_Calculator"))
        assertEquals("My-Calculator", PathSanitizer.sanitizeProjectName("  My-Calculator "))
        assertNull(PathSanitizer.sanitizeProjectName(" My Calculator ")) // space rejected
        // live warning fires for spaces and suggests the underscore fix
        val spaceError = PathSanitizer.projectNameLiveError("My Calculator")
        assertEquals(true, spaceError?.contains("underscore"))
        assertNull(PathSanitizer.projectNameLiveError("My_Calculator")) // valid → no error
        assertEquals("", PathSanitizer.projectNameLiveError("")) // empty → neutral
        assertNull(PathSanitizer.sanitizeProjectName("../x"))
        assertNull(PathSanitizer.sanitizeProjectName("a".repeat(49)))
        assertNull(PathSanitizer.sanitizeProjectName("a/b"))
    }

    @Test
    fun `id suggestion`() {
        assertEquals("calculator", PathSanitizer.suggestId("Calculator"))
        assertEquals("my-app", PathSanitizer.suggestId("My App!"))
        assertEquals("app-2nd", PathSanitizer.suggestId("2nd"))
        assertEquals("app", PathSanitizer.suggestId("!!!"))
    }
}
