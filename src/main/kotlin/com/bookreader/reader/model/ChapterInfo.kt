package com.bookreader.reader.model

/**
 * One chapter of the book currently being read.
 *
 * A chapter is only a *pointer* plus a title: the text itself is fetched lazily
 * (online modes) or sliced out of the already-loaded file text (local modes), so
 * a 3000-chapter online novel costs nothing until it is actually read.
 *
 * @property index  0-based position in the book
 * @property title  display title
 * @property url    absolute URL (online) or the absolute file path (local/epub)
 * @property startOffset offset of the chapter's first character inside the
 *   already-loaded book text, or -1 when the text is not loaded yet
 */
data class ChapterInfo(
    val index: Int,
    val title: String,
    val url: String = "",
    val startOffset: Int = -1,
) {
    /** 1-based number shown in the UI. */
    val number: Int get() = index + 1
}
