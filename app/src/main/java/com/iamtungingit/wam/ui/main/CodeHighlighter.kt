package com.iamtungingit.wam.ui.main

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.iamtungingit.wam.R
import com.iamtungingit.wam.util.CodeTokenizer

/**
 * Applies the custom [CodeTokenizer] output to the code editor as foreground/style
 * spans (improvement 4: syntax highlighting with zero third-party dependencies).
 * Files above the tokenizer cap stay plain so typing never blocks on low-end phones.
 */
object CodeHighlighter {

    private const val SPAN_FLAGS = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    fun highlight(editor: EditText, fileName: String, text: Editable?) {
        if (text == null || text.isEmpty()) return
        if (text.length > CodeTokenizer.HIGHLIGHT_CAP) return
        val spans = CodeTokenizer.tokenize(CodeTokenizer.langFor(fileName), text.toString())
        if (spans.isEmpty()) return

        for (s in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) text.removeSpan(s)
        for (s in text.getSpans(0, text.length, StyleSpan::class.java)) text.removeSpan(s)

        val ctx = editor.context
        for (sp in spans) {
            if (sp.end <= sp.start || sp.end > text.length) continue
            val (colorRes, bold) = styleFor(sp.kind)
            text.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, colorRes)),
                sp.start, sp.end, SPAN_FLAGS
            )
            if (bold) {
                text.setSpan(StyleSpan(Typeface.BOLD), sp.start, sp.end, SPAN_FLAGS)
            }
        }
    }

    private fun styleFor(kind: CodeTokenizer.Kind): Pair<Int, Boolean> = when (kind) {
        CodeTokenizer.Kind.TAG -> R.color.code_tag to true
        CodeTokenizer.Kind.ATTR_NAME -> R.color.code_attr to false
        CodeTokenizer.Kind.ATTR_VALUE -> R.color.code_string to false
        CodeTokenizer.Kind.STRING -> R.color.code_string to false
        CodeTokenizer.Kind.COMMENT -> R.color.code_comment to false
        CodeTokenizer.Kind.NUMBER -> R.color.code_number to false
        CodeTokenizer.Kind.KEYWORD -> R.color.code_keyword to true
        CodeTokenizer.Kind.PROPERTY -> R.color.code_property to false
        CodeTokenizer.Kind.LITERAL -> R.color.code_literal to true
        CodeTokenizer.Kind.PUNCT -> 0 to false
    }

    /** Convenience for dialogs/previews that only need colors, not spans. */
    fun colorOf(context: Context, kind: CodeTokenizer.Kind): Int {
        val (res, _) = styleFor(kind)
        return if (res == 0) 0 else ContextCompat.getColor(context, res)
    }
}
