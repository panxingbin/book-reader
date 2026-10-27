package com.bookreader.reader.util

import com.bookreader.reader.model.ChapterInfo

/**
 * Splits a plain text into chapters using a user-supplied regular expression —
 * the 本地阅读模式 / 本地全章节阅读模式 behaviour documented by the reference
 * plugin ("选择小说文件位置，根据小说【章节名】写一个简单的正则").
 *
 * The match is a `find` (not a whole-line match) so the default pattern
 * `^第.*章.*` behaves as documented: anchoring it with `^` keeps a stray
 * sentence in the middle of the prose from becoming a chapter.
 */
object LocalChapterSplitter {

    /**
     * Find the chapter boundaries of [text].
     *
     * @return one [ChapterInfo] per matching line, carrying the absolute
     *   character offset of that line inside [text] (after the BOM) so the
     *   caller can slice the chapter's body out of the same string.
     */
    fun split(text: String, pattern: Regex): List<ChapterInfo> {
        val clean = text.removePrefix("\uFEFF")
        val out = ArrayList<ChapterInfo>()
        var offset = 0

        for (line in clean.split('\n')) {
            val trimmed = line.trim().trim('\u200B', '\u200E', '\u200F')
            if (trimmed.isNotEmpty() && pattern.containsMatchIn(trimmed)) {
                out.add(
                    ChapterInfo(
                        index = out.size,
                        title = trimmed.take(80),
                        url = "",
                        startOffset = offset,
                    )
                )
            }
            // `line` keeps its '\r' here on purpose: on a CRLF file the line
            // occupies line.length + 1 characters, which is what we add.
            offset += line.length + 1
        }
        return out
    }

    /** True when [pattern] recognises at least one chapter in [text]. */
    fun recognisesAny(text: String, pattern: Regex): Boolean =
        split(text, pattern).isNotEmpty()

    /** The body of chapter [index], given the boundaries from [split]. */
    fun chapterBody(text: String, chapters: List<ChapterInfo>, index: Int): String {
        if (chapters.isEmpty()) return text
        val clean = text.removePrefix("\uFEFF")
        val start = chapters[index].startOffset.coerceAtLeast(0)
        val end = if (index + 1 < chapters.size) chapters[index + 1].startOffset else clean.length
        return clean.substring(start, end.coerceAtLeast(start))
    }
}
