package com.bookreader.reader.model

import com.bookreader.reader.ReaderMode

/**
 * Everything currently loaded for reading: the chapter list and the flattened
 * list of **display lines** the reader pages through.
 *
 * `lines` is what the user actually sees. Depending on the mode it holds
 *
 *  * one chapter (普通 chapter modes — the next chapter is appended on demand), or
 *  * the whole book (全章节 modes, 简易 modes).
 *
 * @property title    book title shown in the tool window / history
 * @property mode     the mode this content was loaded for
 * @property chapters chapter pointers, in reading order (may be empty)
 * @property lines    display lines, already wrapped at 单行展示最大字数.
 *   **Mutable on purpose**: 普通模式 appends one chapter at a time as the reader
 *   reaches it, and rebuilding the list on every append would be O(n²) across a
 *   long novel. [chapterLineStarts] is mutated in step with it.
 * @property chapterLineStarts index into [lines] where each chapter begins;
 *   parallel to [chapters] when every chapter is loaded, otherwise only the
 *   entries that are known (a value of -1 means "not loaded yet")
 * @property wholeBook `true` when [lines] already contains every chapter
 */
data class BookContent(
    val title: String,
    val mode: ReaderMode,
    val chapters: List<ChapterInfo>,
    val lines: MutableList<String>,
    val chapterLineStarts: IntArray,
    val wholeBook: Boolean,
) {
    /** Index of the chapter that contains display line [lineIndex], or -1. */
    fun chapterAtLine(lineIndex: Int): Int {
        var best = -1
        for (i in chapterLineStarts.indices) {
            val start = chapterLineStarts[i]
            if (start < 0) continue
            if (start <= lineIndex) best = i else break
        }
        return best
    }

    /** First display line of chapter [chapterIndex], or -1 when unknown. */
    fun lineOfChapter(chapterIndex: Int): Int =
        chapterLineStarts.getOrElse(chapterIndex) { -1 }

    /** A one-line description for the tool window header. */
    fun describe(): String = buildString {
        append(title)
        if (chapters.isNotEmpty()) append("  ·  ").append(chapters.size).append(" 章")
        append("  ·  ").append(lines.size).append(" 行")
    }
}
