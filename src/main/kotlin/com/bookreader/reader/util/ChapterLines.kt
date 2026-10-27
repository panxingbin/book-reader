package com.bookreader.reader.util

import com.bookreader.reader.ReaderSettings

/**
 * Turns one chapter's raw text into the display lines the reader pages through:
 * wrapped at 单行展示最大字数, then stripped of 内容过滤 matches.
 *
 * Shared by the loader (whole-book modes) and the engine (lazy chapter loading),
 * so a chapter looks identical whichever way it was reached.
 */
object ChapterLines {

    /** Wrap + filter [raw]; returns an empty list when nothing survives. */
    fun wrapAndFilter(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()

        val settings = ReaderSettings.instance()
        val filters = settings.filterPatterns()
        val wrapped = TextWrapper.wrap(raw, settings.maxCharsPerLine)
        if (filters.isEmpty()) return wrapped

        return wrapped.filterNot { line -> filters.any { it.containsMatchIn(line) } }
    }
}
