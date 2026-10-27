package com.bookreader.reader

import com.bookreader.reader.model.ChapterInfo
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import org.jdom.Element

/** One row of the 历史记录 dialog. */
data class HistoryEntry(
    var title: String,
    var modeName: String,
    var path: String,
    var chapterCount: Int,
    var lineCount: Int,
    var position: Int,
    var visitedAt: Long,
)

/**
 * What the reader remembers between sessions: the book it was on, where in it,
 * and the recent history.
 *
 * Stored per project (a project that is closed and reopened should come back to
 * the same book) and written by hand as JDOM, matching the style used elsewhere
 * in this plugin.
 *
 * The chapter list is persisted too, so 历史记录 can show "320 章 · 12000 行"
 * without re-fetching anything. Restoring a remote book still has to fetch it
 * again; the saved chapter list makes that a progress indicator rather than a
 * dead end.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "BookReaderState",
    storages = [Storage("book-reader-state.xml")],
)
class ReadingState(private val project: Project) : PersistentStateComponent<Element> {

    var modeName: String = ReaderMode.DEFAULT.name
        private set
    var path: String = ""
        private set
    var chapterIndex: Int = 0
        private set
    var lineIndex: Int = 0
        private set

    /**
     * Offset inside [chapterIndex], kept apart from [lineIndex].
     *
     * Restoring needs the *chapter-relative* offset: the same chapter starts at a
     * different absolute line depending on whether the book was loaded lazily or in
     * full, so restoring an absolute index into the other mode would land on a
     * different sentence.
     */
    var lineInChapter: Int = 0
        private set

    var wholeBook: Boolean = false
        private set

    private var chapters: List<ChapterInfo> = emptyList()

    /** Recently read books, newest first. */
    private val history = ArrayList<HistoryEntry>()

    fun remember(
        mode: ReaderMode,
        path: String,
        chapterIndex: Int,
        lineInChapter: Int,
        lineIndex: Int,
        whole: Boolean,
        list: List<ChapterInfo>,
    ) {
        this.modeName = mode.name
        this.path = path
        this.chapterIndex = chapterIndex
        this.lineInChapter = lineInChapter
        this.lineIndex = lineIndex
        this.wholeBook = whole

        // The chapter list changes once per load, not once per page turn, so it is
        // only re-stored when it actually differs. Otherwise every save serialises
        // a 3000-chapter list that has not moved.
        if (this.chapters != list) this.chapters = list
    }

    /** Saved chapters, so history rows can report the size without re-reading. */
    fun chapters(): List<ChapterInfo> = chapters

    // ---- history -----------------------------------------------------------

    /** Record a read (or update the entry for the same path), newest first. */
    fun addHistory(entry: HistoryEntry) {
        history.removeAll { it.path == entry.path && it.modeName == entry.modeName }
        history.add(0, entry)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
    }

    fun history(): List<HistoryEntry> = history.toList()

    fun clearHistory() = history.clear()

    fun removeHistory(entry: HistoryEntry) {
        history.removeAll { it.path == entry.path && it.modeName == entry.modeName }
    }

    // ---- persistence -------------------------------------------------------

    override fun getState(): Element {
        val root = Element("bookReaderState")
        root.setAttribute("mode", modeName)
        root.setAttribute("path", path)
        root.setAttribute("chapterIndex", chapterIndex.toString())
        root.setAttribute("lineIndex", lineIndex.toString())
        root.setAttribute("lineInChapter", lineInChapter.toString())
        root.setAttribute("wholeBook", wholeBook.toString())
        for (c in chapters) {
            val e = Element("chapter")
            e.setAttribute("title", c.title)
            e.setAttribute("url", c.url)
            e.setAttribute("offset", c.startOffset.toString())
            root.addContent(e)
        }
        for (h in history) {
            val e = Element("history")
            e.setAttribute("title", h.title)
            e.setAttribute("mode", h.modeName)
            e.setAttribute("path", h.path)
            e.setAttribute("chapterCount", h.chapterCount.toString())
            e.setAttribute("lineCount", h.lineCount.toString())
            e.setAttribute("position", h.position.toString())
            e.setAttribute("visitedAt", h.visitedAt.toString())
            root.addContent(e)
        }
        return root
    }

    override fun loadState(state: Element) {
        modeName = state.getAttributeValue("mode") ?: ReaderMode.DEFAULT.name
        path = state.getAttributeValue("path").orEmpty()
        chapterIndex = state.getAttributeValue("chapterIndex")?.toIntOrNull() ?: 0
        lineIndex = state.getAttributeValue("lineIndex")?.toIntOrNull() ?: 0
        lineInChapter = state.getAttributeValue("lineInChapter")?.toIntOrNull() ?: 0
        wholeBook = state.getAttributeValue("wholeBook")?.toBooleanStrictOrNull() ?: false

        chapters = state.getChildren("chapter").mapNotNull { e ->
            val title = e.getAttributeValue("title") ?: return@mapNotNull null
            ChapterInfo(
                index = 0,
                title = title,
                url = e.getAttributeValue("url").orEmpty(),
                startOffset = e.getAttributeValue("offset")?.toIntOrNull() ?: -1,
            )
        }.mapIndexed { i, c -> c.copy(index = i) }

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
                )
            )
        }
    }

    companion object {
        const val MAX_HISTORY = 30

        fun getInstance(project: Project): ReadingState =
            project.getService(ReadingState::class.java)
    }
}
