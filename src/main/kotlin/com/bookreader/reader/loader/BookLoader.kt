package com.bookreader.reader.loader

import com.bookreader.reader.ReadingEngine
import com.bookreader.reader.ReaderLog
import com.bookreader.reader.ReaderMode
import com.bookreader.reader.ReaderSettings
import com.bookreader.reader.local.EpubChapterReader
import com.bookreader.reader.model.BookContent
import com.bookreader.reader.model.ChapterInfo
import com.bookreader.reader.remote.RemoteChapterReader
import com.bookreader.reader.remote.SiteConfigRepository
import com.bookreader.reader.util.ChapterLines
import com.bookreader.reader.util.LocalChapterSplitter
import com.bookreader.reader.util.TextWrapper
import java.io.File

/**
 * Turns a (mode, source) pair into a [BookContent] the engine can page through.
 *
 * Everything here blocks: it reads files and issues HTTP requests, so callers
 * run it on a background thread and hand the result to
 * [com.bookreader.reader.ReadingEngine.setContent] afterwards.
 *
 * Both long-running loops (全章节 modes, where every chapter is fetched up front)
 * consult [cancelled] between chapters and report through [progress] — without
 * that, a 3000-chapter online novel could not be stopped once started.
 */
object BookLoader {

    /** Progress sink for the 全章节 modes. */
    fun interface Progress {
        fun report(done: Int, total: Int, label: String)
    }

    /** A [Progress] that ignores everything, for callers that do not show progress. */
    val NO_PROGRESS = Progress { _, _, _ -> }

    /** Result of a load attempt. */
    sealed interface Result {
        /** A book was produced; [startLine] is where the reader should resume. */
        data class Ok(val book: BookContent, val startLine: Int, val provider: ReadingEngine.ChapterTextProvider?) : Result

        /** Nothing could be read; [message] is user-facing and already localised. */
        data class Failure(val message: String) : Result

        /** The user cancelled the load (全章节 modes report this between chapters). */
        data object Cancelled : Result
    }

    /**
     * Load [source] (URL or file path) in [mode].
     *
     * @param startChapter chapter to position the reader at
     * @param startLine offset **inside that chapter**, not an absolute line index
     * @param cancelled polled between chapters; return `true` to abort
     * @param progress called with `done/total` while a whole book is fetched
     */
    fun load(
        mode: ReaderMode,
        source: String,
        startChapter: Int = 0,
        startLine: Int = 0,
        cancelled: () -> Boolean = { false },
        progress: Progress = NO_PROGRESS,
    ): Result {
        val settings = ReaderSettings.instance()
        return when (mode) {
            ReaderMode.REMOTE, ReaderMode.REMOTE_FULL ->
                remote(mode, source, settings, startChapter, startLine, cancelled, progress)

            ReaderMode.LOCAL, ReaderMode.LOCAL_FULL ->
                localText(mode, source, settings, startChapter, startLine, cancelled, progress)

            ReaderMode.EPUB, ReaderMode.EPUB_FULL ->
                epub(mode, source, settings, startChapter, startLine, cancelled, progress)

            ReaderMode.SIMPLE_ARTICLE, ReaderMode.SIMPLE_TEXT ->
                simple(mode, source, settings, startLine)
        }
    }

    // ---- online ------------------------------------------------------------

    private fun remote(
        mode: ReaderMode,
        url: String,
        settings: ReaderSettings,
        startChapter: Int,
        startLine: Int,
        cancelled: () -> Boolean,
        progress: Progress,
    ): Result {
        val timeoutMs = settings.remoteTimeoutSeconds.coerceIn(1, 120) * 1000
        val repository = SiteConfigRepository.getInstance()
        val config = repository.configFor(url)
            ?: return Result.Failure(
                "当前在线配置：${com.bookreader.reader.remote.Http.hostOf(url) ?: url} 未在 gitee 配置，" +
                    "请前往菜单【网站节点调试】解析成功该网站后，到 gitee 提交 pr 即可阅读。" +
                    "（适配方式见 gitee 说明）",
            )
        if (!config.isUsable()) {
            return Result.Failure("当前网站配置缺少 chapterPath / contentPath，无法解析")
        }

        ReaderLog.load("解析文章目录 URL 开始：$url")
        val catalog = RemoteChapterReader.catalog(url, config, timeoutMs)
        if (catalog.chapters.isEmpty()) {
            return Result.Failure(
                "根据 chapterPath：${config.chapterPath}，获取节点数为空，" +
                    "请检查是否为【目录页面的 URL】或【尝试重新获取 FullPath 节点】。",
            )
        }
        ReaderLog.load("解析文章目录 URL 完成，共：${catalog.chapters.size} 章")

        val title = catalog.title.ifBlank { "小说 · ${com.bookreader.reader.remote.Http.hostOf(url) ?: url}" }
        val provider = ReadingEngine.ChapterTextProvider { index ->
            catalog.chapters.getOrNull(index)?.let { RemoteChapterReader.chapterText(it.url, config, timeoutMs) }
        }

        return finish(
            mode, title, catalog.chapters, provider, startChapter, startLine, cancelled, progress, source = url,
        ) { examined, total ->
            "解析章节正文失败：根据 contentPath 获取内容为空。" +
                "已检查 $examined/$total 章，全部为空 —— 请检查网站内容是否正常显示，" +
                "或在【网站节点调试】里重新确认 contentPath。"
        }
    }

    // ---- local text --------------------------------------------------------

    private fun localText(
        mode: ReaderMode,
        path: String,
        settings: ReaderSettings,
        startChapter: Int,
        startLine: Int,
        cancelled: () -> Boolean,
        progress: Progress,
    ): Result {
        val file = File(path)
        if (!file.isFile) return Result.Failure("文件不存在：$path")

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return Result.Failure("读取文件出错：$path")

        val pattern = settings.chapterPattern()
            ?: return Result.Failure("章节正则无效：${settings.chapterRegex}")

        val chapters = LocalChapterSplitter.split(text, pattern)
        if (chapters.isEmpty()) {
            return Result.Failure(
                "读取章节为 0，1、检查文件编码是否为 UTF-8 ｜ " +
                    "2、检查正则 ${settings.chapterRegex} 与文章标题是否匹配（在线正则网站测试）",
            )
        }
        ReaderLog.load("根据正则 ${settings.chapterRegex} 全文共：${chapters.size} 章 / ${text.length} 字")

        val title = file.nameWithoutExtension
        val provider = ReadingEngine.ChapterTextProvider { index ->
            LocalChapterSplitter.chapterBody(text, chapters, index).takeIf { it.isNotBlank() }
        }

        return finish(mode, title, chapters, provider, startChapter, startLine, cancelled, progress, source = path) { examined, total ->
            "解析章节正文失败：内容为空（已检查 $examined/$total 章）。" +
                "1、检查文件编码是否为 UTF-8 ｜ 2、检查章节正则 ${settings.chapterRegex} 是否匹配标题。"
        }
    }

    // ---- epub --------------------------------------------------------------

    private fun epub(
        mode: ReaderMode,
        path: String,
        settings: ReaderSettings,
        startChapter: Int,
        startLine: Int,
        cancelled: () -> Boolean,
        progress: Progress,
    ): Result {
        val book = EpubChapterReader.read(path)
            ?: return Result.Failure("解析章节为 0，请检查 epub 文件是否正常（无法解决，可加 QQ 群联系群主）")

        val provider = ReadingEngine.ChapterTextProvider { index -> book.content(index).takeIf { it.isNotBlank() } }

        return finish(mode, book.title, book.chapters, provider, startChapter, startLine, cancelled, progress, source = path) { examined, total ->
            "该 epub 里没有可提取的文字：$total 个章节中已检查 $examined 个，全部为空。" +
                "另有 ${book.skipped} 个纯图片页（封面/书名页等）已跳过。" +
                "如果这是扫描版（整本都是图片），本插件无法阅读。"
        }
    }

    /**
     * Build the [BookContent] for a chaptered source.
     *
     * 普通模式 loads only the first chapter **that has text** and appends the rest
     * as the reader walks forward; 全章节模式 loads everything. Either way the
     * returned start line is resolved after the book is built, so switching between
     * the two surfaces lands on the same sentence.
     *
     * Skipping forward matters in practice: epubs begin with a cover and a title
     * page, and the first online chapter sometimes fails to parse. Giving up on
     * chapter 1 in that case is useless — the reader would rather start at the
     * first chapter that actually reads.
     */
    private fun finish(
        mode: ReaderMode,
        title: String,
        chapters: List<ChapterInfo>,
        provider: ReadingEngine.ChapterTextProvider,
        startChapter: Int,
        startLine: Int,
        cancelled: () -> Boolean,
        progress: Progress,
        source: String,
        noTextMessage: (examined: Int, total: Int) -> String,
    ): Result {
        val from = startChapter.coerceIn(0, chapters.size - 1)

        if (mode.isFullChapter) {
            val book = buildWholeBook(mode, title, chapters, provider, cancelled, progress, source)
                ?: return Result.Cancelled
            if (book.lines.isEmpty()) return Result.Failure(noTextMessage(chapters.size, chapters.size))
            return Result.Ok(book, positionIn(book, from, startLine), provider)
        }

        var examined = 0
        for (index in from until chapters.size) {
            if (cancelled()) return Result.Cancelled
            examined++

            val lines = readChapter(provider, index) ?: continue
            val starts = IntArray(chapters.size) { if (it == index) 0 else -1 }
            val book = BookContent(title, mode, chapters, lines, starts, wholeBook = false, source = source)

            if (index != from) {
                ReaderLog.load("第 ${from + 1} 章没有正文，已从第 ${index + 1} 章开始阅读。")
            }
            // startLine only describes the chapter the caller asked for.
            val offset = if (index == from) startLine else 0
            return Result.Ok(book, positionIn(book, index, offset), provider)
        }

        return Result.Failure(noTextMessage(examined, chapters.size))
    }

    // ---- simple modes ------------------------------------------------------

    private fun simple(mode: ReaderMode, path: String, settings: ReaderSettings, startLine: Int): Result {
        val file = File(path)
        if (!file.isFile) return Result.Failure("文件不存在：$path")

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return Result.Failure("读取文件出错：$path")

        val lines = when (mode) {
            ReaderMode.SIMPLE_ARTICLE -> {
                ReaderLog.load(
                    "开始分割内容，当前设置：单行展示最大字数：${settings.maxCharsPerLine}、" +
                        "开启尾部行号显示：${settings.showTailLineNumber}",
                )
                TextWrapper.splitByChars(text, settings.maxCharsPerLine)
            }
            ReaderMode.SIMPLE_TEXT -> TextWrapper.splitByLines(text)
            else -> return Result.Failure("该模式不支持简易阅读")
        }
        if (lines.isEmpty()) return Result.Failure("文件内容为空：$path")

        ReaderLog.load("分割数据结束，共 ${lines.size} 行")
        val book = BookContent(
            file.nameWithoutExtension, mode, emptyList(), ArrayList(lines), IntArray(0),
            wholeBook = true, source = path,
        )
        // 简易 modes have no chapters, so `startLine` is already absolute; honour
        // it so 历史记录 / reopening the IDE resumes where the user stopped.
        return Result.Ok(book, startLine = startLine.coerceIn(0, (lines.size - 1).coerceAtLeast(0)), provider = null)
    }

    // ---- shared ------------------------------------------------------------

    /** Absolute line index of `chapter` + `offsetInChapter`, clamped into the book. */
    private fun positionIn(book: BookContent, chapter: Int, offsetInChapter: Int): Int {
        if (book.lines.isEmpty()) return 0
        val base = book.chapterLineStarts.getOrElse(chapter) { 0 }.coerceAtLeast(0)
        return (base + offsetInChapter.coerceAtLeast(0)).coerceIn(0, book.lines.size - 1)
    }

    /** Read one chapter and wrap it into display lines; null when it has no text. */
    private fun readChapter(
        provider: ReadingEngine.ChapterTextProvider,
        index: Int,
    ): MutableList<String>? = runCatching {
        val raw = provider.textOf(index) ?: return@runCatching null
        val lines = ChapterLines.wrapAndFilter(raw)
        if (lines.isEmpty()) null else ArrayList(lines)
    }.getOrNull()

    /**
     * Concatenate every chapter into one continuous line list (全章节 modes).
     *
     * Returns `null` when the user cancelled part-way through.
     */
    private fun buildWholeBook(
        mode: ReaderMode,
        title: String,
        chapters: List<ChapterInfo>,
        provider: ReadingEngine.ChapterTextProvider,
        cancelled: () -> Boolean,
        progress: Progress,
        source: String,
    ): BookContent? {
        val lines = ArrayList<String>()
        val starts = IntArray(chapters.size)
        var failures = 0

        for (i in chapters.indices) {
            if (cancelled()) {
                ReaderLog.load("已取消解析（已完成 ${i}/${chapters.size} 章）。")
                return null
            }
            starts[i] = lines.size

            val raw = runCatching { provider.textOf(i) }.getOrNull()
            if (raw.isNullOrBlank()) {
                failures++
            } else {
                lines += ChapterLines.wrapAndFilter(raw)
            }

            // Report often enough to feel alive, rarely enough not to flood the EDT.
            if (i % 5 == 0 || i == chapters.lastIndex) {
                progress.report(i + 1, chapters.size, chapters[i].title)
            }
        }

        if (failures > 0) ReaderLog.load("有 $failures 章内容为空，已跳过。")
        ReaderLog.load("全章节解析完成，共 ${lines.size} 行")
        return BookContent(title, mode, chapters, lines, starts, wholeBook = true, source = source)
    }
}
