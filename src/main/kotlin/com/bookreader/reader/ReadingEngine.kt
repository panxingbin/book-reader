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
 * In chapter modes the first chapter is loaded eagerly and the rest lazily
 * (`next` at the end of a chapter pulls in the following one), so a 3000-chapter
 * online novel costs one request until it is actually read.
 */
@Service(Service.Level.PROJECT)
class ReadingEngine(private val project: Project) {

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

        // The setting is read once per page rather than once per line.
        val tail = ReaderSettings.instance().showTailLineNumber
        return book.lines.subList(from, to).map { display(it, tail) }
    }

    // ---- paging ------------------------------------------------------------

    /** Lines per page for the current book. */
    fun step(): Int {
        val book = content ?: return 1
        return if (book.mode.isFullChapter) {
            ReaderSettings.instance().linesPerPage.coerceAtLeast(1)
        } else {
            1
        }
    }

    /**
     * Move forward one page.
     *
     * @return `true` when the position actually moved (false at the end of the
     *   book, which is how the UI reports "读完了").
     */
    fun next(): Boolean {
        val book = content ?: return false
        val target = position + step()

        if (target < book.lines.size) {
            position = target
            persist()
            fire()
            return true
        }

        // End of the loaded lines: pull in the next chapter when there is one.
        val current = book.chapterAtLine(position)
        if (current >= 0 && current + 1 < book.chapters.size && book.lineOfChapter(current + 1) < 0) {
            val loaded = ensureChapter(current + 1)
            if (loaded && position + step() < book.lines.size) {
                position += step()
                persist()
                fire()
                return true
            }
        }

        position = (book.lines.size - 1).coerceAtLeast(0)
        persist()
        fire()
        return false
    }

    /** Move back one page. */
    fun previous(): Boolean {
        val book = content ?: return false
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
    }

    /**
     * Jump to a chapter.
     *
     * In a lazily loaded book the chapter is fetched first. A **backwards** jump
     * resets the loaded window at that chapter: appending it after the text that is
     * already loaded would make the reader skip chapters when it walks forward again.
     */
    fun goToChapter(index: Int) {
        val book = content ?: return
        if (index < 0 || index >= book.chapters.size) return

        if (book.lineOfChapter(index) < 0) {
            if (book.wholeBook) return
            book.lines.clear()
            java.util.Arrays.fill(book.chapterLineStarts, -1)
            if (!ensureChapter(index)) return
        }

        position = book.lineOfChapter(index).coerceAtLeast(0)
        persist()
        fire()
    }

    /** Offset of the reader inside its current chapter — the value `startLine` wants. */
    fun lineIndexInChapter(): Int {
        val book = content ?: return position
        val chapter = currentChapter()
        val chapterStart = if (chapter >= 0) book.lineOfChapter(chapter).coerceAtLeast(0) else 0
        return (position - chapterStart).coerceAtLeast(0)
    }

    /** Load chapter [index] into [content] if it is not there yet. */
    private fun ensureChapter(index: Int): Boolean {
        val book = content ?: return false
        if (index < 0 || index >= book.chapters.size) return false
        val loader = provider ?: return false
        if (book.lineOfChapter(index) >= 0) return true

        val raw = loader.textOf(index) ?: return false
        val wrapped = com.bookreader.reader.util.ChapterLines.wrapAndFilter(raw)
        if (wrapped.isEmpty()) return false

        // Append IN PLACE. `book.lines + wrapped` would copy the entire list on
        // every chapter, i.e. O(n²) over a long novel read from start to finish.
        val start = book.lines.size
        book.lines.addAll(wrapped)
        if (index < book.chapterLineStarts.size) book.chapterLineStarts[index] = start
        lastChapterLoaded = index
        return true
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
        display(line, ReaderSettings.instance().showTailLineNumber)

    private fun display(line: String, tailLineNumber: Boolean): String =
        if (tailLineNumber) "$line  [${position + 1}]" else line

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
                    if (!next()) stopAutoNext()
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
        stopAutoNext()
        fire()
    }
}
