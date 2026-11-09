package com.iamtungingit.wam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the custom code tokenizer used by the editor's syntax highlighting. */
class CodeTokenizerTest {

    private fun kinds(spans: List<CodeTokenizer.Span>) = spans.map { it.kind }

    @Test
    fun `html tags attributes and values`() {
        val html = "<div class=\"box\" id=main><!-- note --></div>"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.HTML, html)
        val k = kinds(spans)
        assertTrue(CodeTokenizer.Kind.TAG in k)
        assertTrue(CodeTokenizer.Kind.ATTR_NAME in k)
        assertTrue(CodeTokenizer.Kind.ATTR_VALUE in k)
        assertTrue(CodeTokenizer.Kind.COMMENT in k)
        // The comment span must cover exactly "<!-- note -->"
        val comment = spans.first { it.kind == CodeTokenizer.Kind.COMMENT }
        assertEquals("<!-- note -->", html.substring(comment.start, comment.end))
    }

    @Test
    fun `html with embedded script produces js tokens`() {
        val html = "<script>var x = 1;</script>"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.HTML, html)
        assertTrue(CodeTokenizer.Kind.KEYWORD in kinds(spans)) // "var"
    }

    @Test
    fun `css properties numbers and comments`() {
        val css = "/* c */ body { color: #FFF; margin: 0px; } @media print { }"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.CSS, css)
        val k = kinds(spans)
        assertTrue(CodeTokenizer.Kind.COMMENT in k)
        assertTrue(CodeTokenizer.Kind.PROPERTY in k)
        assertTrue(CodeTokenizer.Kind.NUMBER in k)
        assertTrue(CodeTokenizer.Kind.KEYWORD in k) // @media
    }

    @Test
    fun `js keywords strings and comments`() {
        val js = "// hi\nvar s = \"ok\"; function f() { return true; }"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.JS, js)
        val k = kinds(spans)
        assertTrue(CodeTokenizer.Kind.COMMENT in k)
        assertTrue(CodeTokenizer.Kind.STRING in k)
        assertTrue(k.contains(CodeTokenizer.Kind.KEYWORD)) // var/function/return
        assertTrue(CodeTokenizer.Kind.LITERAL in k) // true
    }

    @Test
    fun `json distinguishes keys from string values`() {
        val json = "{\"name\": \"wap\", \"n\": 42, \"ok\": true}"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.JSON, json)
        val key = spans.first { json.substring(it.start, it.end) == "\"name\"" }
        val value = spans.first { json.substring(it.start, it.end) == "\"wap\"" }
        assertEquals(CodeTokenizer.Kind.PROPERTY, key.kind)
        assertEquals(CodeTokenizer.Kind.STRING, value.kind)
        assertTrue(CodeTokenizer.Kind.NUMBER in kinds(spans))
        assertTrue(CodeTokenizer.Kind.LITERAL in kinds(spans))
    }

    @Test
    fun `langFor maps extensions`() {
        assertEquals(CodeTokenizer.Lang.HTML, CodeTokenizer.langFor("index.html"))
        assertEquals(CodeTokenizer.Lang.CSS, CodeTokenizer.langFor("style.CSS"))
        assertEquals(CodeTokenizer.Lang.JS, CodeTokenizer.langFor("app.js"))
        assertEquals(CodeTokenizer.Lang.JSON, CodeTokenizer.langFor("manifest.json"))
        assertEquals(CodeTokenizer.Lang.PLAIN, CodeTokenizer.langFor("notes.txt"))
    }

    @Test
    fun `spans are ordered and in bounds`() {
        val src = "<html><body><p style=\"color:red\">x</p></body></html>"
        val spans = CodeTokenizer.tokenize(CodeTokenizer.Lang.HTML, src)
        var last = 0
        for (s in spans) {
            assertTrue(s.start >= last)
            assertTrue(s.end > s.start && s.end <= src.length)
            last = s.end
        }
    }
}
