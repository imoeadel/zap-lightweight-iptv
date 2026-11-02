package com.moe.zap.core

import java.text.Normalizer

/**
 * Turns titles and typed queries into the same plain form, so "Beyoncé", "BEYONCE" and "beyonce" match,
 * and so do Arabic spellings that differ only in hamza, teh marbuta, alef maqsura or diacritics.
 * The output holds lowercase letters and digits separated by single spaces: safe to index and to query.
 */
object Search {
    private val MARKS = Regex("\\p{M}+")

    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false
        for (raw in MARKS.replace(decomposed, "")) {
            val c = foldArabic(raw.lowercaseChar())
            if (c == SKIP) continue
            if (c.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(c)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    private const val SKIP = '\u0000'

    private fun foldArabic(c: Char): Char = when (c) {
        'آ', 'أ', 'إ', 'ٱ' -> 'ا' // alef variants -> alef
        'ة' -> 'ه' // teh marbuta -> heh
        'ى' -> 'ي' // alef maqsura -> yeh
        'ؤ' -> 'و' // waw with hamza -> waw
        'ئ' -> 'ي' // yeh with hamza -> yeh
        'ـ' -> SKIP // kashida is only decoration
        '٠', '١', '٢', '٣', '٤',
        '٥', '٦', '٧', '٨', '٩' -> ('0'.code + (c.code - 0x0660)).toChar()
        else -> c
    }

    /** An FTS4 MATCH expression requiring every typed word as a word prefix, or null if nothing is searchable. */
    fun ftsQuery(input: String): String? {
        val words = normalize(input).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        // Quoted so words such as "or", "and", "not" and "near" stay plain text instead of FTS operators.
        // The asterisk belongs inside the quotes: FTS4 silently ignores one placed after them.
        return words.joinToString(" ") { "\"$it*\"" }
    }
}
