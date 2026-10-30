package com.bookreader.reader

import com.bookreader.reader.model.ChapterInfo
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * What **this window** remembers between sessions: the book it was on, and where
 * in it.
 *
 * Deliberately still per project — two IDE windows may read two different books
 * at two different positions, and neither should overwrite the other. Everything
 * that must be shared across windows (书籍目录, 历史记录, 上次读的书) lives in
 * [BookLibrary], the application-level counterpart.
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

        migrateLegacyHistory(state)
    }

    /**
     * Releases before 3.6.0 kept 历史记录 in this project file. Hand those rows to
     * the shared [BookLibrary] once, so an upgrade keeps every existing entry
     * (and the per-project lists are merged instead of one winning).
     *
     * The children are not written back by [getState] any more, so after the next
     * save the legacy block is gone.
     */
    private fun migrateLegacyHistory(state: Element) {
        val legacy = state.getChildren("history").mapNotNull { e ->
            val path = e.getAttributeValue("path") ?: return@mapNotNull null
            val position = e.getAttributeValue("position")?.toIntOrNull() ?: 0
            HistoryEntry(
                title = e.getAttributeValue("title").orEmpty(),
                modeName = e.getAttributeValue("mode").orEmpty(),
                path = path,
                chapterCount = e.getAttributeValue("chapterCount")?.toIntOrNull() ?: 0,
                lineCount = e.getAttributeValue("lineCount")?.toIntOrNull() ?: 0,
                position = position,
                visitedAt = e.getAttributeValue("visitedAt")?.toLongOrNull() ?: 0L,
                // Old rows only kept the absolute line; resume from chapter 0 at
                // that offset, which is exact for whole-book / 简易 modes.
                chapterIndex = 0,
                lineInChapter = position,
            )
        }
        if (legacy.isNotEmpty()) BookLibrary.instance().mergeHistory(legacy)
    }

    companion object {
        fun getInstance(project: Project): ReadingState =
            project.getService(ReadingState::class.java)
    }
}
