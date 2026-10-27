package com.bookreader.reader.util

/**
 * Splits a paragraph into display lines of at most [maxChars] characters.
 *
 * Chinese text has no spaces, so a hard character cut is usually right; the only
 * refinement is to avoid cutting through an ASCII word (`connection` →
 * `connec` + `tion`), which happens whenever a paragraph mixes both scripts.
 */
object TextWrapper {

    /**
     * Wrap [text] into display lines.
     *
     * Blank source lines are dropped: in 普通 mode every display line is one
     * press of 下一页, so empty lines would only waste presses.
     */
    fun wrap(text: String, maxChars: Int): List<String> {
        if (maxChars <= 0) return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { chunk(it, maxChars) }
            .toList()
    }

    /** Wrap a single paragraph. */
    fun chunk(line: String, maxChars: Int): List<String> {
        if (maxChars <= 0 || line.length <= maxChars) return listOf(line)

        val out = ArrayList<String>()
        var start = 0
        while (start < line.length) {
            var end = (start + maxChars).coerceAtMost(line.length)
            if (end < line.length) {
                // Walk back over a run of ASCII letters/digits so we do not split a word.
                var cut = end
                while (cut > start && isAsciiWordChar(line[cut - 1])) cut--
                if (cut > start + maxChars / 2) end = cut
            }
            out.add(line.substring(start, end).trim())
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun isAsciiWordChar(c: Char): Boolean =
        c.code < 128 && (c.isLetterOrDigit() || c == '\'' || c == '-')

    /**
     * Split the text into fixed-size character blocks — 简易文章阅读模式.
     *
     * Unlike [wrap] this ignores the paragraph structure completely, exactly as
     * the reference documents ("仅按照【单行展示最大字数】的值进行内容分割").
     */
    fun splitByChars(text: String, maxChars: Int): List<String> {
        if (maxChars <= 0) return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = ArrayList<String>()
        var index = 0
        val flat = text.replace("\r\n", "\n").replace('\r', '\n')
        while (index < flat.length) {
            val end = (index + maxChars).coerceAtMost(flat.length)
            val piece = flat.substring(index, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            index = end
        }
        return out
    }

    /** 简易文本阅读模式: one display line per file line. */
    fun splitByLines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .toList()
}
