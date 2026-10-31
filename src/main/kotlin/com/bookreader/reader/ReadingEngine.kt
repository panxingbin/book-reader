package com.bookreader.reader

import com.bookreader.reader.model.BookContent
import com.bookreader.reader.model.ChapterInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reading runtime: which book is open, where in it the reader is, and how
 * pages move.
 *
 * A "page" is a **display-line window**:
 *
 *  * 普通模式 (discreet) advances **one display line** per press of 下一页, which
 *    is what makes a novel look like a status-bar indicator;
 *  * 全章节模式 advances [ReaderSettings.linesPerPage] lines, which is the
 *    reading surface in the Read tool window.
 *
 * In chapter modes the first chapter is loaded eagerly and the rest lazily.
 * The next chapter is fetched **off the EDT** — either prefetched while the
 * reader is still [PREFETCH_AHEAD_LINES] lines away from the end of the loaded
 * window, or on demand at the boundary — so an online novel never freezes the
 * IDE while the chapter request is in flight.
 */
@Service(Service.Level.PROJECT)
class ReadingEngine(private val project: Project) {

    /** Result of pressing 下一页 — the UI uses it for "读完 / 加载中" feedback. */
    enum class PageTurn {
        /** The position moved. */
        MOVED,

        /** The next chapter is being fetched in the background; the page will
         *  turn on its own when the text arrives (while the reader stayed put). */
        LOADING,

        /** The reader is at the very end of the book. */
        END,
    }

    /** Called whenever the visible text may have changed (page, book, toggle…). */
    fun interface Listener {
        fun readerChanged()
    }

    /** Supplies the text of a chapter on demand; registered together with a book. */
    fun interface ChapterTextProvider {
        fun textOf(chapterIndex: Int): String?
    }

    @Volatile
    private var content: BookContent? = null

    @Volatile
    private var position: Int = 0

    @Volatile
    private var autoNext: Boolean = false

    @Volatile
    private var provider: ChapterTextProvider? = null

    @Volatile
    private var lastChapterLoaded: Int = -1

    /**
     * One background chapter fetch, requested by [requestChapterLoad]. Single
     * slot: a newer request supersedes an older one (the generation counter
     * below makes the stale completion drop its result).
     */
    private data class ChapterLoadRequest(val index: Int, val resetWindow: Boolean, val advanceWhenDone: Boolean)

    @Volatile
    private var pendingLoad: ChapterLoadRequest? = null

    private val loadGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Consecutive chapter fetches that came back empty — see [MAX_LOAD_FAILURES]. */
    @Volatile
    private var loadFailures: Int = 0

    /**
     * Lines per page to use when 设置里「全章节每页行数」is `0` (按窗口高度自适应).
     * The Read tool window measures its viewport and stores the value here; `0`
     * means "not measured yet".
     */
    @Volatile
    var adaptiveLinesPerPage: Int = 0

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val autoTimer = Timer("book-reader-auto-next", true)

    /**
     * The one scheduled auto-page task.
     *
     * Holding the reference is what makes toggling safe: the original version
     * scheduled a fresh task on every start and never cancelled the old one, so
     * off → on → off → on left four timers running and each tick turned four pages.
     */
    @Volatile
    private var autoTask: TimerTask? = null

    val book: BookContent? get() = content
    val lineIndex: Int get() = position
    val isAutoNext: Boolean get() = autoNext

    /** The book's chapters, or an empty list for the 简易 modes. */
    val chapters: List<ChapterInfo> get() = content?.chapters.orEmpty()

    // ---- content -----------------------------------------------------------

    /**
     * Replace the current book. Called from a background thread once the loader
     * has produced the chapter list and the first chunk of display lines.
     */
    fun setContent(book: BookContent, startLine: Int = 0, provider: ChapterTextProvider? = null) {
        this.content = book
        this.position = startLine.coerceIn(0, (book.lines.size - 1).coerceAtLeast(0))
        this.provider = provider
        this.lastChapterLoaded = if (book.chapters.isEmpty()) -1 else 0
        // A new book invalidates any chapter fetch still in flight for the old one.
        pendingLoad = null
        loadGeneration.incrementAndGet()
        loadFailures = 0
        persist()
        fire()
    }

    /** The display line at the current position, or null when nothing is loaded. */
    fun currentLine(): String? {
        val book = content ?: return null
        if (book.lines.isEmpty()) return null
        return display(book.lines[position.coerceIn(0, book.lines.size - 1)])
    }

    /**
     * The lines of the current page: one line in 普通模式, [ReaderSettings.linesPerPage]
     * in 全章节模式.
     */
    fun pageLines(): List<String> {
        val book = content ?: return emptyList()
        if (book.lines.isEmpty()) return emptyList()
        val from = position.coerceIn(0, book.lines.size - 1)
        val to = (from + step()).coerceAtMost(book.lines.size)
        if (from >= to) return emptyList()

        // The setting is read once per page rather than once per line. The 尾部行号
        // is the line's own display-line number (the number 内容跳转 wants), not
        // the page's first line repeated on every row.
        val tail = ReaderSettings.instance().showTailLineNumber
        return book.lines.subList(from, to).mapIndexed { i, line ->
            if (tail) "$line  [${from + i + 1}]" else line
        }
    }

    // ---- paging ------------------------------------------------------------

    /** Lines per page for the current book. */
    fun step(): Int {
        val book = content ?: return 1
        if (!book.mode.isFullChapter) return 1
        val configured = ReaderSettings.instance().linesPerPage
        if (configured == 0) {
            // 按窗口高度自适应: the Read window measures its viewport; until it
            // has, fall back to a small sane page instead of 0.
            return adaptiveLinesPerPage.takeIf { it > 0 } ?: 1
        }
        return configured.coerceAtLeast(1)
    }

    /**
     * Move forward one page.
     *
     * At the end of the loaded lines the next chapter is fetched **on a pooled
     * thread** (see [requestChapterLoad]) and the outcome is [PageTurn.LOADING]:
     * the page turns by itself once the text arrives, as long as the reader did
     * not move elsewhere in the meantime. Only the true end of the book returns
     * [PageTurn.END].
     */
    fun nextPageTurn(): PageTurn {
        val book = content ?: return PageTurn.END
        val target = position + step()

        if (target < book.lines.size) {
            position = target
            persist()
            fire()
            maybePrefetch()
            return PageTurn.MOVED
        }

        // End of the loaded lines: pull in the next chapter when there is one.
        val current = book.chapterAtLine(position)
        val nextIndex = current + 1
        if (current >= 0 && nextIndex < book.chapters.size && book.lineOfChapter(nextIndex) < 0) {
            // A fetch already in flight (prefetch or the previous press) turns
            // the page by itself when it lands — report it instead of "读完".
            if (pendingLoad != null || requestChapterLoad(nextIndex, resetWindow = false, advanceWhenDone = true)) {
                return PageTurn.LOADING
            }
        }

        position = (book.lines.size - 1).coerceAtLeast(0)
        persist()
        fire()
        return PageTurn.END
    }

    /** Move forward one page. `true` when the position actually moved. */
    fun next(): Boolean = nextPageTurn() == PageTurn.MOVED

    /** Move back one page. */
    fun previous(): Boolean {
        if (content == null) return false
        if (position <= 0) return false
        position = (position - step()).coerceAtLeast(0)
        persist()
        fire()
        return true
    }

    /** Jump to a display line. */
    fun goToLine(index: Int) {
        val book = content ?: return
        position = index.coerceIn(0, (book.lines.size - 1).coerceAtLeast(0))
        persist()
        fire()
        maybePrefetch()
    }

    /**
     * Jump to a chapter.
     *
     * In a lazily loaded book the chapter is fetched first — **off the EDT**, so
     * an online novel no longer freezes the IDE while jumping. A jump to a
     * chapter that is not in the loaded window resets the window at that
     * chapter: appending it after the text that is already loaded would make the
     * reader skip chapters when it walks forward again.
     */
    fun goToChapter(index: Int) {
        val book = content ?: return
        if (index < 0 || index >= book.chapters.size) return

        val start = book.lineOfChapter(index)
        if (start >= 0) {
            position = start.coerceAtLeast(0)
            persist()
            fire()
            maybePrefetch()
            return
        }
        if (book.wholeBook) return

        requestChapterLoad(index, resetWindow = true, advanceWhenDone = false)
    }

    /**
     * Fetch chapter [index] on a pooled thread and merge it into
     * [BookContent.lines] on the EDT when the text arrives.
     *
     * The old code ran this request synchronously inside `next()`, i.e. on the
     * EDT — every chapter boundary in an online book froze the IDE for up to
     * remoteTimeoutSeconds. Now the fetch is background and only the (cheap)
     * merge touches the UI thread. A newer request supersedes an older one.
     *
     * @return `false` when nothing can be loaded (no provider, whole book
     *   already loaded, chapter already present) — callers treat that as "not
     *   loadable" rather than "wait for it".
     */
    private fun requestChapterLoad(index: Int, resetWindow: Boolean, advanceWhenDone: Boolean): Boolean {
        val book = content ?: return false
        if (book.wholeBook || book.lineOfChapter(index) >= 0) return false
        val loader = provider ?: return false

        // The advance-on-arrival only applies while the reader stays on the very
        // line that requested the fetch — a jump in the meantime must not be
        // yanked forward by a stale boundary press.
        val requestedAt = position
        pendingLoad = ChapterLoadRequest(index, resetWindow, advanceWhenDone)
        val generation = loadGeneration.incrementAndGet()

        ApplicationManager.getApplication().executeOnPooledThread {
            val raw = runCatching { loader.textOf(index) }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (generation != loadGeneration.get()) return@invokeLater
                pendingLoad = null
                val current = content
                if (current == null || current !== book) return@invokeLater

                val wrapped = raw?.let { com.bookreader.reader.util.ChapterLines.wrapAndFilter(it) }.orEmpty()
                if (wrapped.isEmpty()) {
                    loadFailures++
                    // Auto reading must not hammer a broken site forever: stop
                    // after a few empty fetches in a row (manual retries stay
                    // possible — every press starts a fresh request).
                    if (autoNext && loadFailures >= MAX_LOAD_FAILURES) stopAutoNext()
                    ReaderLog.warn("第 ${index + 1} 章内容为空，稍后再试或重新初始化。")
                    fire()
                    return@invokeLater
                }
                loadFailures = 0

                if (resetWindow) {
                    // Same contract as the old synchronous reset: start the
                    // loaded window at [index] so walking forward cannot skip
                    // chapters.
                    current.lines.clear()
                    java.util.Arrays.fill(current.chapterLineStarts, -1)
                    current.lines.addAll(wrapped)
                    if (index < current.chapterLineStarts.size) current.chapterLineStarts[index] = 0
                    position = 0
                } else {
                    // Append IN PLACE. `book.lines + wrapped` would copy the entire
                    // list on every chapter, i.e. O(n²) over a long novel read
                    // from start to finish.
                    val start = current.lines.size
                    current.lines.addAll(wrapped)
                    if (index < current.chapterLineStarts.size) current.chapterLineStarts[index] = start
                    if (advanceWhenDone && position == requestedAt && position + step() < current.lines.size) {
                        position += step()
                    }
                }
                lastChapterLoaded = index
                persist()
                fire()
                maybePrefetch()
            }
        }
        return true
    }

    /**
     * Prefetch the next chapter while the reader is still [PREFETCH_AHEAD_LINES]
     * display lines away from the end of the loaded window, so crossing a
     * chapter boundary costs no wait at all.
     */
    private fun maybePrefetch() {
        val book = content ?: return
        if (book.wholeBook || pendingLoad != null) return
        val current = book.chapterAtLine(position)
        val nextIndex = current + 1
        if (current < 0 || nextIndex >= book.chapters.size) return
        if (book.lineOfChapter(nextIndex) >= 0) return
        if (book.lines.size - position > PREFETCH_AHEAD_LINES) return
        requestChapterLoad(nextIndex, resetWindow = false, advanceWhenDone = false)
    }

    /** Offset of the reader inside its current chapter — the value `startLine` wants. */
    fun lineIndexInChapter(): Int {
        val book = content ?: return position
        val chapter = currentChapter()
        val chapterStart = if (chapter >= 0) book.lineOfChapter(chapter).coerceAtLeast(0) else 0
        return (position - chapterStart).coerceAtLeast(0)
    }

    /** The chapter the reader is currently inside, or -1. */
    fun currentChapter(): Int = content?.chapterAtLine(position) ?: -1

    /** Progress within the whole book, 0..100. */
    fun progressPercent(): Int {
        val book = content ?: return 0
        if (book.lines.isEmpty()) return 0
        return (position * 100 / book.lines.size).coerceIn(0, 100)
    }

    // ---- display helpers ---------------------------------------------------

    /** The line as the user sees it, with 尾部行号 when enabled. */
    private fun display(line: String): String =
        if (ReaderSettings.instance().showTailLineNumber) "$line  [${position + 1}]" else line

    // ---- auto next ---------------------------------------------------------

    fun startAutoNext() {
        if (autoNext) return
        autoNext = true
        scheduleAutoTask()
        fire()
    }

    fun stopAutoNext() {
        autoNext = false
        // Cancel the timer as well as flipping the flag, so a later start cannot
        // end up with two live tasks.
        autoTask?.cancel()
        autoTask = null
        fire()
    }

    private fun scheduleAutoTask() {
        autoTask?.cancel()
        val interval = ReaderSettings.instance().autoNextSeconds.coerceIn(1, 600) * 1000L
        val task = object : TimerTask() {
            override fun run() {
                if (!autoNext) return
                ApplicationManager.getApplication().invokeLater {
                    if (!autoNext) return@invokeLater
                    // LOADING keeps auto reading alive: the pending chapter fetch
                    // turns the page by itself when the text arrives. Only the
                    // true end of the book stops it.
                    if (nextPageTurn() == PageTurn.END) stopAutoNext()
                }
            }
        }
        autoTask = task
        autoTimer.scheduleAtFixedRate(task, interval, interval)
    }

    fun toggleAutoNext(): Boolean {
        if (autoNext) stopAutoNext() else startAutoNext()
        return autoNext
    }

    /**
     * Nudge the 自动下一页 interval by [delta] seconds — the 速度− / 速度+ buttons.
     * Reschedules the live timer so the change takes effect immediately.
     */
    fun adjustAutoNextSeconds(delta: Int) {
        val settings = ReaderSettings.instance()
        settings.autoNextSeconds = (settings.autoNextSeconds + delta).coerceIn(1, 600)
        if (autoNext) scheduleAutoTask()
        fire()
    }

    // ---- listeners ---------------------------------------------------------

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun fire() {
        for (listener in listeners) {
            runCatching { listener.readerChanged() }
        }
    }

    // ---- persistence -------------------------------------------------------

    /** Remember where we are, so reopening the project (or the IDE) resumes. */
    private fun persist() {
        val book = content ?: return
        val mode = ReaderMode.byNameOrNull(book.mode.name) ?: return

        val chapter = currentChapter()
        val chapterStart = if (chapter >= 0) book.lineOfChapter(chapter).coerceAtLeast(0) else 0
        val lineInChapter = (position - chapterStart).coerceAtLeast(0)
        val path = sourcePathOf(book)

        ReadingState.getInstance(project).remember(
            mode = mode,
            path = path,
            chapterIndex = chapter,
            lineInChapter = lineInChapter,
            lineIndex = position,
            whole = book.wholeBook,
            list = book.chapters,
        )

        // The application-level snapshot: a window opened later resumes this
        // book at this position, while this window keeps its own progress in
        // ReadingState above (each window's progress stays independent).
        BookLibrary.instance().rememberBook(mode.name, path, chapter, lineInChapter, position)
    }

    private fun sourcePathOf(book: BookContent): String =
        book.source.takeIf { it.isNotBlank() }
            ?: book.chapters.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: book.title

    fun clear() {
        content = null
        provider = null
        position = 0
        lastChapterLoaded = -1
        // Drop any in-flight chapter fetch for the cleared book.
        pendingLoad = null
        loadGeneration.incrementAndGet()
        loadFailures = 0
        stopAutoNext()
        fire()
    }

    private companion object {
        /** How far ahead of the loaded window's end the next chapter prefetch starts. */
        const val PREFETCH_AHEAD_LINES = 20

        /** Empty chapter fetches in a row before 自动阅读 gives up. */
        const val MAX_LOAD_FAILURES = 3
    }
}
