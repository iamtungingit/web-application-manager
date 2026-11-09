package com.iamtungingit.wam.util

/**
 * Custom code tokenizer for the WAM code editor (improvement requests 2 + 4).
 *
 * A small, dependency-free scanner that produces colored-token spans for the four
 * file types a WAP project uses: HTML, CSS, JS and JSON. It is pure JVM logic so it
 * is unit-testable, and it is tuned for the editor's size cap (1 MB): files above
 * [HIGHLIGHT_CAP] return no spans (the editor then shows plain text, which stays
 * fast and never blocks typing on low-end devices).
 */
object CodeTokenizer {

    enum class Lang { HTML, CSS, JS, JSON, PLAIN }

    enum class Kind {
        TAG, ATTR_NAME, ATTR_VALUE, COMMENT, STRING, NUMBER, KEYWORD, PROPERTY, LITERAL, PUNCT
    }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** Beyond this many characters the editor renders plain text (performance guard). */
    const val HIGHLIGHT_CAP = 200_000

    fun langFor(fileName: String): Lang = when (fileName.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> Lang.HTML
        "css" -> Lang.CSS
        "js", "mjs" -> Lang.JS
        "json", "webmanifest" -> Lang.JSON
        else -> Lang.PLAIN
    }

    /** Tokenizes [text] (already known to be ≤ HIGHLIGHT_CAP). Returns spans, ordered. */
    fun tokenize(lang: Lang, text: String): List<Span> {
        if (lang == Lang.PLAIN || text.isEmpty()) return emptyList()
        val out = ArrayList<Span>(64)
        when (lang) {
            Lang.HTML -> tokenizeHtml(text, 0, text.length, out)
            Lang.CSS -> tokenizeCss(text, 0, text.length, out)
            Lang.JS -> tokenizeJs(text, 0, text.length, out)
            Lang.JSON -> tokenizeJson(text, out)
            Lang.PLAIN -> {}
        }
        return out
    }

    // ------------------------------------------------------------------ HTML

    private fun tokenizeHtml(text: String, from: Int, to: Int, out: MutableList<Span>) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '<' && text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4).let { if (it == -1) to else it + 3 }
                    out.add(Span(i, minOf(end, to), Kind.COMMENT))
                    i = minOf(end, to)
                }
                c == '<' && i + 1 < to && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!') -> {
                    var j = i + 1
                    val closing = text[j] == '/'
                    if (closing) j++
                    val nameStart = j
                    while (j < to && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_' || text[j] == '!')) j++
                    val name = text.substring(nameStart, j).lowercase()
                    if (j > nameStart) {
                        out.add(Span(if (closing) i + 1 else i, j, Kind.TAG))
                        // scan the open tag region for attributes
                        if (!closing) {
                            j = scanHtmlAttrs(text, j, to, out)
                            // embedded CSS/JS (scanHtmlAttrs returns the index past '>')
                            if (name == "script" || name == "style") {
                                val closeTag = "</$name"
                                val contentStart = j
                                val closeIdx = text.indexOf(closeTag, contentStart, ignoreCase = true)
                                val contentEnd = if (closeIdx == -1) to else closeIdx
                                if (contentEnd > contentStart) {
                                    if (name == "script") tokenizeJs(text, contentStart, contentEnd, out)
                                    else tokenizeCss(text, contentStart, contentEnd, out)
                                }
                                i = if (closeIdx == -1) to else closeIdx
                                continue
                            }
                        }
                        i = j
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
    }

    /** Scans attributes until the closing '>' of the open tag; returns index at/after '>'. */
    private fun scanHtmlAttrs(text: String, from: Int, to: Int, out: MutableList<Span>): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '>' -> return i + 1
                c == '<' -> return i // malformed; let the outer loop treat it as a new tag
                c == '/' && i + 1 < to && text[i + 1] == '>' -> return i + 2
                c.isLetter() || c == '_' || c == '-' -> {
                    val start = i
                    while (i < to && (text[i].isLetterOrDigit() || text[i] in "_-:")) i++
                    val end = i
                    // skip ws + '='
                    var k = i
                    while (k < to && text[k].isWhitespace()) k++
                    if (k < to && text[k] == '=') {
                        out.add(Span(start, end, Kind.ATTR_NAME))
                        k++
                        while (k < to && text[k].isWhitespace()) k++
                        if (k < to && (text[k] == '"' || text[k] == '\'')) {
                            val q = text[k]
                            val vStart = k
                            val close = text.indexOf(q, k + 1)
                            val vEnd = if (close == -1) to else close + 1
                            out.add(Span(vStart, minOf(vEnd, to), Kind.ATTR_VALUE))
                            i = minOf(vEnd, to)
                        } else {
                            val uStart = k
                            while (k < to && !text[k].isWhitespace() && text[k] != '>') k++
                            if (k > uStart) {
                                out.add(Span(uStart, k, Kind.ATTR_VALUE))
                            }
                            i = k
                        }
                    } else {
                        i = end
                    }
                }
                else -> i++
            }
        }
        return to
    }

    // ------------------------------------------------------------------ CSS

    private fun tokenizeCss(text: String, from: Int, to: Int, out: MutableList<Span>) {
        var i = from
        var propStart = -1
        while (i < to) {
            val c = text[i]
            when {
                c == '/' && i + 1 < to && text[i + 1] == '*' -> {
                    if (propStart != -1 && i > propStart) {
                        out.add(Span(propStart, i, Kind.PROPERTY)); propStart = -1
                    }
                    val end = text.indexOf("*/", i + 2).let { if (it == -1) to else it + 2 }
                    out.add(Span(i, minOf(end, to), Kind.COMMENT))
                    i = minOf(end, to)
                }
                c == '"' || c == '\'' -> {
                    if (propStart != -1 && i > propStart) {
                        out.add(Span(propStart, i, Kind.PROPERTY)); propStart = -1
                    }
                    val close = text.indexOf(c, i + 1)
                    val end = if (close == -1) to else close + 1
                    out.add(Span(i, minOf(end, to), Kind.STRING))
                    i = minOf(end, to)
                }
                c == '@' -> {
                    val start = i
                    i++
                    while (i < to && (text[i].isLetterOrDigit() || text[i] == '-')) i++
                    out.add(Span(start, i, Kind.KEYWORD))
                }
                c == '#' && i + 1 < to && text[i + 1].isLetterOrDigit() -> {
                    val start = i
                    i++
                    while (i < to && text[i].isLetterOrDigit()) i++
                    out.add(Span(start, i, Kind.NUMBER))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < to && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '%')) i++
                    out.add(Span(start, i, Kind.NUMBER))
                }
                c == ':' -> {
                    if (propStart != -1 && i > propStart) {
                        out.add(Span(propStart, i, Kind.PROPERTY)); propStart = -1
                    }
                    out.add(Span(i, i + 1, Kind.PUNCT))
                    i++
                }
                c == '{' || c == '}' || c == ';' -> {
                    propStart = -1
                    out.add(Span(i, i + 1, Kind.PUNCT))
                    i++
                }
                c == '!' && text.regionMatches(i, "!important", 0, "!important".length) -> {
                    out.add(Span(i, i + "!important".length, Kind.KEYWORD))
                    i += "!important".length
                }
                c.isWhitespace() -> i++
                else -> {
                    if (propStart == -1) propStart = i
                    i++
                }
            }
        }
    }

    // ------------------------------------------------------------------ JS

    private val JS_KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "export", "extends", "finally", "for", "function",
        "if", "import", "in", "instanceof", "let", "new", "of", "return", "static",
        "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while",
        "with", "yield", "async", "await"
    )

    private fun tokenizeJs(text: String, from: Int, to: Int, out: MutableList<Span>) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '/' && i + 1 < to && text[i + 1] == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it == -1) to else it }
                    out.add(Span(i, minOf(end, to), Kind.COMMENT))
                    i = minOf(end, to)
                }
                c == '/' && i + 1 < to && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it == -1) to else it + 2 }
                    out.add(Span(i, minOf(end, to), Kind.COMMENT))
                    i = minOf(end, to)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val q = c
                    var j = i + 1
                    while (j < to) {
                        if (text[j] == '\\') { j += 2; continue }
                        if (text[j] == q) { j++; break }
                        j++
                    }
                    out.add(Span(i, minOf(j, to), Kind.STRING))
                    i = minOf(j, to)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < to && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                    out.add(Span(start, i, Kind.NUMBER))
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < to && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    when {
                        word in JS_KEYWORDS -> out.add(Span(start, i, Kind.KEYWORD))
                        word == "true" || word == "false" || word == "null" || word == "undefined" ->
                            out.add(Span(start, i, Kind.LITERAL))
                        i < to && text[i] == '(' -> out.add(Span(start, i, Kind.PROPERTY)) // call
                    }
                }
                else -> i++
            }
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun tokenizeJson(text: String, out: MutableList<Span>) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < n) {
                        if (text[j] == '\\') { j += 2; continue }
                        if (text[j] == '"') { j++; break }
                        j++
                    }
                    // key or value: a key is followed (after ws) by ':'
                    var k = j
                    while (k < n && text[k].isWhitespace()) k++
                    val isKey = k < n && text[k] == ':'
                    out.add(Span(i, minOf(j, n), if (isKey) Kind.PROPERTY else Kind.STRING))
                    i = minOf(j, n)
                }
                c.isDigit() || (c == '-' && i + 1 < n && text[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < n && (text[i].isDigit() || text[i] in ".eE+-")) {
                        if (text[i] in "+-" && text[i - 1] !in "eE") break
                        i++
                    }
                    out.add(Span(start, i, Kind.NUMBER))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < n && text[i].isLetter()) i++
                    val word = text.substring(start, i)
                    if (word == "true" || word == "false" || word == "null") {
                        out.add(Span(start, i, Kind.LITERAL))
                    }
                }
                else -> i++
            }
        }
    }
}
