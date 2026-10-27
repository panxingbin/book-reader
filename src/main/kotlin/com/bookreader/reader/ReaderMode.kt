package com.bookreader.reader

/**
 * The eight reading modes of the plugin, mirroring the reference
 * `chapter_reader` menu (Tools → Read).
 *
 * Two orthogonal ideas are encoded here:
 *
 *  * **where the text is shown** — [isFullChapter] `false` means the discreet
 *    "普通" mode (one line in the status bar / a small popup), `true` means the
 *    "全章节" mode (the whole book in the **Read** tool window).
 *  * **where the text comes from and how it is split** — [source] and [split].
 */
enum class ReaderMode(
    /** Text shown in the Tools → Read menu and in dialogs. */
    val title: String,
    val source: Source,
    val split: Split,
    /** `true` = 全章节 mode: rendered in the Read tool window. */
    val isFullChapter: Boolean,
) {
    /** 在线阅读模式 — one chapter at a time, from a novel site. */
    REMOTE("在线阅读模式", Source.REMOTE, Split.CHAPTERS, isFullChapter = false),

    /** 在线全章节阅读模式 — the whole book, from a novel site. */
    REMOTE_FULL("在线全章节阅读模式", Source.REMOTE, Split.CHAPTERS, isFullChapter = true),

    /** 本地阅读模式 — one chapter at a time, from a local .txt. */
    LOCAL("本地阅读模式", Source.LOCAL_FILE, Split.CHAPTERS, isFullChapter = false),

    /** 本地全章节阅读模式 — the whole .txt. */
    LOCAL_FULL("本地全章节阅读模式", Source.LOCAL_FILE, Split.CHAPTERS, isFullChapter = true),

    /** Epub阅读模式 — one chapter at a time, from a local .epub. */
    EPUB("Epub阅读模式", Source.EPUB, Split.CHAPTERS, isFullChapter = false),

    /** Epub全章节阅读模式 — the whole .epub. */
    EPUB_FULL("Epub全章节阅读模式", Source.EPUB, Split.CHAPTERS, isFullChapter = true),

    /** 简易文章阅读模式 — a plain file, wrapped at 单行展示最大字数. */
    SIMPLE_ARTICLE("简易文章阅读模式", Source.LOCAL_FILE, Split.MAX_CHARS, isFullChapter = false),

    /** 简易文本阅读模式 — a plain file, split only on the system line separator. */
    SIMPLE_TEXT("简易文本阅读模式", Source.LOCAL_FILE, Split.LINE_SEPARATOR, isFullChapter = false),
    ;

    /** Where the raw text comes from. */
    enum class Source {
        /** A novel site described by a [com.bookreader.reader.remote.SiteConfig]. */
        REMOTE,

        /** A local `.txt` / plain file. */
        LOCAL_FILE,

        /** A local `.epub`. */
        EPUB,
    }

    /** How the raw text becomes the list of displayed lines. */
    enum class Split {
        /** Split into chapters (regex / epub spine), then wrap each paragraph. */
        CHAPTERS,

        /** No chapters: wrap the whole file at 单行展示最大字数. */
        MAX_CHARS,

        /** No chapters: one display line per file line. */
        LINE_SEPARATOR,
    }

    /** `true` when the mode reads from the network. */
    val isRemote: Boolean get() = source == Source.REMOTE

    /** `true` when the mode needs a chapter-title regex from the user. */
    val needsChapterRegex: Boolean
        get() = split == Split.CHAPTERS && (source == Source.LOCAL_FILE || source == Source.EPUB)

    /** The counterpart mode with the other display surface. */
    fun toggleFullChapter(): ReaderMode = when (this) {
        REMOTE -> REMOTE_FULL
        REMOTE_FULL -> REMOTE
        LOCAL -> LOCAL_FULL
        LOCAL_FULL -> LOCAL
        EPUB -> EPUB_FULL
        EPUB_FULL -> EPUB
        SIMPLE_ARTICLE -> SIMPLE_ARTICLE
        SIMPLE_TEXT -> SIMPLE_TEXT
    }

    companion object {
        /** The mode a fresh install starts in. */
        val DEFAULT: ReaderMode = REMOTE

        fun byNameOrNull(name: String?): ReaderMode? =
            entries.firstOrNull { it.name == name }

        /** Modes offered by the "本地阅读" menu group, in menu order. */
        val LOCAL_GROUP: List<ReaderMode> = listOf(LOCAL, LOCAL_FULL, EPUB, EPUB_FULL, SIMPLE_ARTICLE, SIMPLE_TEXT)

        /** Modes offered by the "在线阅读" menu group. */
        val REMOTE_GROUP: List<ReaderMode> = listOf(REMOTE, REMOTE_FULL)
    }
}
