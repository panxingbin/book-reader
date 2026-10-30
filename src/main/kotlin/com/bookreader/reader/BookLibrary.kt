package com.bookreader.reader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import org.jdom.Element

/**
 * One row of the 历史记录 dialog.
 *
 * Fields stay mutable on purpose: the shared [BookLibrary] rewrites [position]
 * and [visitedAt] in place while the book is being read, so the dialog shows
 * "读到第几行" and the newest book first without re-adding the entry.
 */
data class HistoryEntry(
    var title: String,
    var modeName: String,
    var path: String,
    var chapterCount: Int,
    var lineCount: Int,
    /** Absolute display-line index — the 历史记录 row shows this as 读到第几行. */
    var position: Int,
    var visitedAt: Long,
    /**
     * Chapter the reader was inside, and the offset in it.
     *
     * Resuming needs the *chapter-relative* offset: in a lazily loaded book the
     * same chapter starts at a different absolute line than in 全章节 mode, so
     * handing [position] over as `startLine` would land on another sentence.
     */
    var chapterIndex: Int = 0,
    var lineInChapter: Int = 0,
)

/**
 * 书籍库 — the state every IDE window shares.
 *
 * [ReadingState] (project-level) still owns "where *this* window is in its
 * book", so two windows can read two books independently. This
 * application-level service owns everything that must **not** be configured
 * again in a new window:
 *
 *  * [bookDirectory] — the folder the book picker starts in;
 *  * [history] — the 历史记录 list (used to be project-level, so every new
 *    window started with an empty list);
 *  * the last opened book snapshot, so a fresh window reopens it automatically.
 *
 * Persisted by hand as JDOM, like the other components in this plugin.
 */
@Service(Service.Level.APP)
@State(
    name = "BookReaderLibrary",
    storages = [Storage("book-reader-library.xml")],
)
class BookLibrary : PersistentStateComponent<Element> {

    /** Last directory the user picked books from — shared by every window. */
    var bookDirectory: String = ""
        private set

    // ---- last opened book (used to restore a brand-new window) -------------

    var lastModeName: String = ""
        private set
    var lastPath: String = ""
        private set
    var lastChapterIndex: Int = 0
        private set
    var lastLineInChapter: Int = 0
        private set
    var lastLineIndex: Int = 0
        private set

    /** Recently read books, newest first (see [history]). */
    private val history = ArrayList<HistoryEntry>()

    // ---- directory ---------------------------------------------------------

    /** Remember the book directory; the next window starts right there. */
    @Synchronized
    fun rememberDirectory(directory: String) {
        bookDirectory = directory
    }

    // ---- last book ---------------------------------------------------------

    /**
     * Record what is being read right now.
     *
     * Called on every page turn (through `ReadingEngine.persist`), so a window
     * opened later restores the book at the most recent position, while the
     * already-open windows keep their own progress in their project state.
     */
    @Synchronized
    fun rememberBook(
        modeName: String,
        path: String,
        chapterIndex: Int,
        lineInChapter: Int,
        lineIndex: Int,
    ) {
        this.lastModeName = modeName
        this.lastPath = path
        this.lastChapterIndex = chapterIndex
        this.lastLineInChapter = lineInChapter
        this.lastLineIndex = lineIndex

        // Keep the matching history row current: 历史记录 shows "读到第几行"
        // and orders by 最近阅读, both of which move while reading.
        history.firstOrNull { it.path == path && it.modeName == modeName }?.let {
            it.position = lineIndex
            it.chapterIndex = chapterIndex.coerceAtLeast(0)
            it.lineInChapter = lineInChapter
            it.visitedAt = System.currentTimeMillis()
        }
    }

    // ---- history -----------------------------------------------------------

    /** Recently read books, newest first. */
    @Synchronized
    fun history(): List<HistoryEntry> = history.sortedByDescending { it.visitedAt }

    /** Record a read (or update the entry for the same path + mode). */
    @Synchronized
    fun addHistory(entry: HistoryEntry) {
        history.removeAll { it.path == entry.path && it.modeName == entry.modeName }
        history.add(0, entry)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
    }

    @Synchronized
    fun removeHistory(entry: HistoryEntry) {
        history.removeAll { it.path == entry.path && it.modeName == entry.modeName }
    }

    @Synchronized
    fun clearHistory() = history.clear()

    /**
     * Merge rows written by older versions into the per-project state file.
     *
     * Releases before 3.6.0 kept 历史记录 inside `book-reader-state.xml`, i.e. one
     * list per project. [ReadingState.loadState] hands those rows over once;
     * entries already present are kept and the newer `visitedAt` wins.
     */
    @Synchronized
    fun mergeHistory(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return

        val merged = LinkedHashMap<String, HistoryEntry>()
        for (entry in history) merged[keyOf(entry)] = entry
        for (entry in entries) {
            val key = keyOf(entry)
            val existing = merged[key]
            if (existing == null || entry.visitedAt > existing.visitedAt) merged[key] = entry
        }

        history.clear()
        history.addAll(merged.values.sortedByDescending { it.visitedAt }.take(MAX_HISTORY))
    }

    // ---- persistence -------------------------------------------------------

    @Synchronized
    override fun getState(): Element {
        val root = Element("bookReaderLibrary")
        root.setAttribute("bookDirectory", bookDirectory)
        root.setAttribute("lastMode", lastModeName)
        root.setAttribute("lastPath", lastPath)
        root.setAttribute("lastChapterIndex", lastChapterIndex.toString())
        root.setAttribute("lastLineInChapter", lastLineInChapter.toString())
        root.setAttribute("lastLineIndex", lastLineIndex.toString())
        for (h in history) {
            val e = Element("history")
            e.setAttribute("title", h.title)
            e.setAttribute("mode", h.modeName)
            e.setAttribute("path", h.path)
            e.setAttribute("chapterCount", h.chapterCount.toString())
            e.setAttribute("lineCount", h.lineCount.toString())
            e.setAttribute("position", h.position.toString())
            e.setAttribute("visitedAt", h.visitedAt.toString())
            e.setAttribute("chapterIndex", h.chapterIndex.toString())
            e.setAttribute("lineInChapter", h.lineInChapter.toString())
            root.addContent(e)
        }
        return root
    }

    @Synchronized
    override fun loadState(state: Element) {
        bookDirectory = state.getAttributeValue("bookDirectory").orEmpty()
        lastModeName = state.getAttributeValue("lastMode").orEmpty()
        lastPath = state.getAttributeValue("lastPath").orEmpty()
        lastChapterIndex = state.getAttributeValue("lastChapterIndex")?.toIntOrNull() ?: 0
        lastLineInChapter = state.getAttributeValue("lastLineInChapter")?.toIntOrNull() ?: 0
        lastLineIndex = state.getAttributeValue("lastLineIndex")?.toIntOrNull() ?: 0

        history.clear()
        for (e in state.getChildren("history")) {
            history.add(
                HistoryEntry(
                    title = e.getAttributeValue("title").orEmpty(),
                    modeName = e.getAttributeValue("mode").orEmpty(),
                    path = e.getAttributeValue("path").orEmpty(),
                    chapterCount = e.getAttributeValue("chapterCount")?.toIntOrNull() ?: 0,
                    lineCount = e.getAttributeValue("lineCount")?.toIntOrNull() ?: 0,
                    position = e.getAttributeValue("position")?.toIntOrNull() ?: 0,
                    visitedAt = e.getAttributeValue("visitedAt")?.toLongOrNull() ?: 0L,
                    chapterIndex = e.getAttributeValue("chapterIndex")?.toIntOrNull() ?: 0,
                    lineInChapter = e.getAttributeValue("lineInChapter")?.toIntOrNull() ?: 0,
                )
            )
        }
    }

    private fun keyOf(entry: HistoryEntry): String = entry.path + "\u0000" + entry.modeName

    companion object {
        const val MAX_HISTORY = 30

        fun instance(): BookLibrary =
            ApplicationManager.getApplication().getService(BookLibrary::class.java)
                ?: throw IllegalStateException("BookLibrary service is not available")
    }
}
