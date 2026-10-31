package com.bookreader.reader

import com.bookreader.reader.loader.BookLoader
import com.bookreader.reader.ui.ReadPanelHolder
import com.bookreader.reader.ui.ReaderStatusBar
import com.bookreader.reader.ui.dialogs.BookPickerDialog
import com.bookreader.reader.ui.dialogs.ChapterRegexDialog
import com.bookreader.reader.ui.dialogs.PickedBook
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File

/**
 * Glue between the menu actions, the loaders and the two display surfaces.
 *
 * Every reading mode starts here: ask the user for what the mode needs (a URL, a
 * file, a chapter regex), parse it on a background thread with a cancellable
 * progress bar, then hand the result to [ReadingEngine] and refresh the status
 * bar and the **Read** tool window.
 */
@Service(Service.Level.PROJECT)
class ReaderController(private val project: Project) {

    /** `true` after 快速清屏: every surface is blanked until 重新初始化. */
    @Volatile
    var hidden: Boolean = false
        private set

    private val engine: ReadingEngine get() = project.getService(ReadingEngine::class.java)

    // ---- entry points ------------------------------------------------------

    /** Start [mode], asking the user for whatever it needs. */
    fun start(mode: ReaderMode) {
        if (!ensureEnabled()) return

        when (mode.source) {
            ReaderMode.Source.REMOTE -> startRemote(mode)
            ReaderMode.Source.LOCAL_FILE -> {
                if (mode.split == ReaderMode.Split.CHAPTERS) {
                    chooseLocalText(mode) { pick -> startLocalChapters(mode, pick) }
                } else {
                    chooseLocalText(mode) { pick -> load(mode, pick.path, pick.chapterIndex, pick.lineInChapter) }
                }
            }
            ReaderMode.Source.EPUB -> chooseEpub { pick -> load(mode, pick.path, pick.chapterIndex, pick.lineInChapter) }
        }
    }

    private fun startRemote(mode: ReaderMode) {
        val state = ReadingState.getInstance(project)
        // A brand-new window has no project state yet; prefill from the shared
        // "last book" so the URL does not have to be retyped in every window.
        val initial = sequenceOf(state.path, BookLibrary.instance().lastPath)
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .orEmpty()

        val url = Messages.showInputDialog(
            project,
            "请输入小说的【章节目录】URL：",
            mode.title,
            Messages.getQuestionIcon(),
            initial,
            null,
        )?.trim()
        if (url.isNullOrEmpty()) return

        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            Messages.showWarningDialog(project, "URL 需以 http:// 或 https:// 开头。", mode.title)
            return
        }

        // Make sure the site table is available before looking the domain up.
        ensureSiteConfig { load(mode, url) }
    }

    /**
     * 在线阅读 needs the shared site table. It is fetched once per session; when
     * the network is down the cached copy in the settings is used instead.
     */
    private fun ensureSiteConfig(then: () -> Unit) {
        val repository = com.bookreader.reader.remote.SiteConfigRepository.getInstance()
        if (repository.isLoaded) {
            then()
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "读取在线阅读支持站点", false) {
            override fun run(indicator: ProgressIndicator) {
                repository.load()
            }

            override fun onSuccess() = then()
        })
    }

    private fun startLocalChapters(mode: ReaderMode, pick: PickedBook) {
        val settings = ReaderSettings.instance()
        // 续读不再打断：这本书读过（历史里有，或选书对话框直接从历史页签挑的）、
        // 或设置里关掉了询问，就直接按已保存的正则开书；只有没见过的新书才弹
        // 一次输入框。正则想改，去设置页改「章节正则」。
        val seenBefore = pick.fromHistory || BookLibrary.instance().history().any { it.path == pick.path }
        if (!settings.askChapterRegex || seenBefore) {
            load(mode, pick.path, pick.chapterIndex, pick.lineInChapter)
            return
        }

        val dialog = ChapterRegexDialog(project, settings.chapterRegex)
        if (!dialog.showAndGet()) return

        settings.chapterRegex = dialog.regex()
        if (dialog.doNotAskAgain()) settings.askChapterRegex = false
        // A 历史记录 row carries the chapter + offset it was left at; a fresh file
        // pick has 0/0, which is exactly "start at the beginning".
        load(mode, pick.path, pick.chapterIndex, pick.lineInChapter)
    }

    // ---- loading -----------------------------------------------------------

    /** Parse [source] in [mode] on a background thread and show the result. */
    fun load(mode: ReaderMode, source: String, startChapter: Int = 0, startLine: Int = 0) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "解析中：${mode.title}", true) {
            private var result: BookLoader.Result? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "解析中…"
                val started = System.currentTimeMillis()
                result = BookLoader.load(
                    mode = mode,
                    source = source,
                    startChapter = startChapter,
                    startLine = startLine,
                    cancelled = { indicator.isCanceled },
                    progress = BookLoader.Progress { done, total, label ->
                        // 全章节 modes fetch every chapter up front; without the
                        // progress bar the user would see a frozen "解析中…" forever.
                        indicator.isIndeterminate = false
                        indicator.fraction = done.toDouble() / total.coerceAtLeast(1)
                        indicator.text = "正在解析第 $done/$total 章：${label.take(30)}"
                    },
                )
                ReaderLog.load("解析文件完成，耗时：${System.currentTimeMillis() - started}/ms")
            }

            override fun onSuccess() {
                when (val outcome = result) {
                    is BookLoader.Result.Ok -> {
                        hidden = false
                        engine.setContent(outcome.book, outcome.startLine, outcome.provider)
                        recordHistory(outcome.book, source)
                        // 全章节 mode reads in the Read window; without this the
                        // user picks the mode and sees nothing at all happen.
                        if (outcome.book.mode.isFullChapter) activateReadWindow()
                        refresh()
                        announceReady(outcome.book)
                    }
                    is BookLoader.Result.Failure -> {
                        ReaderLog.warn(outcome.message)
                        Messages.showWarningDialog(project, outcome.message, mode.title)
                    }
                    BookLoader.Result.Cancelled -> ReaderLog.load("已取消：${mode.title}")
                    null -> Unit
                }
            }
        })
    }

    private fun announceReady(book: com.bookreader.reader.model.BookContent) {
        if (!ReaderSettings.instance().loadLog) return
        if (book.mode.isFullChapter) {
            ReaderLog.load("全章节阅读解析完成，点击右侧【Read】窗口进行阅读。")
        } else {
            ReaderLog.load(
                "解析文件完成，使用快捷键【上 / 下一页】进行阅读。" +
                    "（默认 Ctrl+Alt+2 / Ctrl+Alt+3）",
            )
        }
    }

    private fun recordHistory(book: com.bookreader.reader.model.BookContent, source: String) {
        // 历史记录 is shared by every window now, so it lives in BookLibrary
        // instead of the per-project ReadingState.
        BookLibrary.instance().addHistory(
            HistoryEntry(
                title = book.title,
                modeName = book.mode.name,
                path = source,
                chapterCount = book.chapters.size,
                lineCount = book.lines.size,
                position = engine.lineIndex,
                visitedAt = System.currentTimeMillis(),
                chapterIndex = engine.currentChapter().coerceAtLeast(0),
                lineInChapter = engine.lineIndexInChapter(),
            )
        )
    }

    /** Re-open an entry from 历史记录. */
    fun openHistory(entry: HistoryEntry) {
        val mode = ReaderMode.byNameOrNull(entry.modeName) ?: ReaderMode.REMOTE
        // `startLine` is an offset inside the chapter, so resume with the stored
        // chapter + offset — handing over the absolute line would land elsewhere.
        load(mode, entry.path, startChapter = entry.chapterIndex, startLine = entry.lineInChapter)
    }

    // ---- display control ---------------------------------------------------

    /** 上一页. */
    fun previousPage(): Boolean {
        val moved = engine.previous()
        refresh()
        return moved
    }

    /** 下一页. */
    fun nextPage(): ReadingEngine.PageTurn {
        val outcome = engine.nextPageTurn()
        refresh()
        return outcome
    }

    /** 上一章 — jump to the previous chapter; at the first one, to the very start. */
    fun previousChapter(): Boolean {
        engine.book ?: return false
        val current = engine.currentChapter()
        return if (current > 0) {
            engine.goToChapter(current - 1)
            refresh()
            true
        } else {
            engine.goToLine(0)
            refresh()
            false
        }
    }

    /** 下一章 — jump to the next chapter; `false` at the last one. */
    fun nextChapter(): Boolean {
        val book = engine.book ?: return false
        val current = engine.currentChapter()
        if (current + 1 >= book.chapters.size) return false
        engine.goToChapter(current + 1)
        refresh()
        return true
    }

    /** 自动下一页【再次按下则停止】. */
    fun toggleAutoNext(): Boolean {
        val on = engine.toggleAutoNext()
        refresh()
        return on
    }

    /** 普通阅读与全章节阅读转换. */
    fun toggleFullChapter() {
        val book = engine.book ?: return
        if (!ensureEnabled()) return

        val target = book.mode.toggleFullChapter()
        if (target == book.mode) return

        // The source is the file path / catalog URL the loader was given; the
        // online modes are the only ones where chapters carry a usable URL, and
        // local .txt chapters carry none at all.
        val source = book.source.takeIf { it.isNotBlank() }
            ?: book.chapters.firstOrNull()?.url?.takeIf { it.isNotBlank() }
        if (source.isNullOrBlank()) {
            // 简易 modes: only the display surface changes, the text stays.
            val converted = book.copy(mode = target)
            engine.setContent(converted, engine.lineIndex)
            if (target.isFullChapter) activateReadWindow()
            refresh()
            return
        }

        // `startLine` is an offset inside the chapter, which is exactly what
        // BookLoader expects, so both surfaces land on the same sentence.
        val chapter = engine.currentChapter().coerceIn(0, book.chapters.size - 1)
        load(target, source, startChapter = chapter, startLine = engine.lineIndexInChapter())
    }

    /** 重新初始化 — re-read the current book from its source. */
    fun restart() {
        val book = engine.book
        hidden = false
        val source = book?.source?.takeIf { it.isNotBlank() }
            ?: book?.chapters?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: ReadingState.getInstance(project).path.takeIf { it.isNotBlank() }
            ?: run { refresh(); return }
        val mode = book?.mode ?: ReaderMode.byNameOrNull(ReadingState.getInstance(project).modeName) ?: ReaderMode.DEFAULT
        load(mode, source)
    }

    /** 快速清屏 — blank every surface at once (the "有人来了" key). */
    fun clearScreen() {
        hidden = true
        engine.stopAutoNext()
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.hide()
        refresh()
    }

    /**
     * 恢复阅读 — bring the surfaces back after 快速清屏 without re-loading the book.
     *
     * [clearScreen] only blanks; the book, position and chapter list stay in
     * [engine], so flipping [hidden] back and redrawing is enough to resume.
     */
    fun showReading() {
        if (!hidden && ReaderSettings.instance().enabled) return
        hidden = false
        refresh()
        // 清屏时 Read 窗口被隐藏了，恢复阅读若书是全章节模式就重新打开并激活，
        // 否则恢复后窗口不会自动回来，用户还以为没恢复成功。
        val book = engine.book
        if (book != null && book.mode.isFullChapter) activateReadWindow()
    }

    /**
     * Called when the **Read** tool window is shown by the platform (its stripe
     * icon, a keyboard shortcut, our own [activateReadWindow]).
     *
     * A window blanked by 快速清屏 keeps [hidden] `true`; if that flag is not
     * lifted the window comes back showing only the "已隐藏" card, which reads as
     * "clicking the icon did nothing". Showing the window is an explicit request
     * to see it, so reveal the content.
     */
    fun revealAfterShow() {
        if (!hidden) return
        hidden = false
        refresh()
    }

    /** 全章节模式 or 恢复阅读 — activate the Read tool window. */
    fun activateReadWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null, true)
    }

    /**
     * Toggle the **Read** tool window the way the right-hand stripe icon does:
     * visible → hide, hidden → show.
     *
     * Re-activating an already visible tool window is a no-op in the platform, so
     * any caller that only called [activateReadWindow] had no way back out.
     *
     * Clearing [hidden] first matters: a window blanked by 快速清屏 keeps
     * [hidden] `true`, and while [hidden] is `true` the Read panel shows only the
     * "已隐藏" card — the window toggles fine but the text never comes back,
     * which reads as "点了没反应".
     */
    fun toggleReadWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return

        if (toolWindow.isVisible) {
            toolWindow.hide(null)
            return
        }

        hidden = false
        toolWindow.activate(null, true)
        refresh()
    }

    /** Push the current state into the status bar and the tool window. */
    fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            ReaderStatusBar.update(project)
            ReadPanelHolder.get(project)?.refresh()
        }
    }

    // ---- restore -----------------------------------------------------------

    /**
     * Restore the book after the project opens.
     *
     * This window's own [ReadingState] wins, so each window keeps its own
     * progress. A window that has never opened a book — the "new window" case —
     * falls back to the shared [BookLibrary] snapshot, so the last book is
     * already open instead of having to be picked again.
     *
     * Only local sources are restored automatically: re-fetching a novel from a
     * website on every project open would be rude to the site and slow to boot.
     */
    fun restoreLast() {
        val state = ReadingState.getInstance(project)
        if (restoreLocal(state.modeName, state.path, state.chapterIndex, state.lineInChapter)) return
        if (state.path.isNotBlank()) return

        val library = BookLibrary.instance()
        restoreLocal(library.lastModeName, library.lastPath, library.lastChapterIndex, library.lastLineInChapter)
    }

    private fun restoreLocal(modeName: String, path: String, chapterIndex: Int, lineInChapter: Int): Boolean {
        if (path.isBlank()) return false
        val mode = ReaderMode.byNameOrNull(modeName) ?: return false
        if (mode.source == ReaderMode.Source.REMOTE) return false
        if (!File(path).isFile) return false

        load(mode, path, chapterIndex, lineInChapter)
        return true
    }

    // ---- helpers -----------------------------------------------------------

    private fun ensureEnabled(): Boolean {
        if (ReaderSettings.instance().enabled) return true
        Messages.showInfoMessage(
            project,
            "插件当前处于关闭状态。请在【插件设置】中开启：是否开启插件 = 是。",
            "Book Reader",
        )
        return false
    }

    /**
     * 打开书籍 — the one place that answers "I set the book directory, now where
     * do I pick a book?".
     *
     * The dialog lists every readable file of the shared directory (txt and epub
     * together) **and** 历史记录, and offers a 阅读方式 combo, so the mode is
     * chosen right there instead of having to guess which of the six menu entries
     * matches the file. Picking a history row resumes it where it was left.
     */
    fun openBookPicker() {
        if (!ensureEnabled()) return

        val directory = initialBookDirectory() ?: chooseBookDirectory() ?: return
        val library = BookLibrary.instance()
        val initialMode = ReaderMode.byNameOrNull(library.lastModeName)
            ?.takeIf { it.source != ReaderMode.Source.REMOTE }
            ?: ReaderMode.LOCAL

        val dialog = BookPickerDialog(
            project = project,
            dialogTitle = "打开书籍",
            extensions = BOOK_EXTENSIONS,
            startDirectory = directory,
            browse = { browseSingleFile("打开书籍", BOOK_EXTENSIONS) },
            modes = ReaderMode.LOCAL_GROUP,
            initialMode = initialMode,
        )
        dialog.show()

        val pick = dialog.chosen ?: return
        val mode = pick.mode ?: initialMode
        pick.file?.let { library.rememberDirectory(it.parentFile?.absolutePath ?: directory.absolutePath) }

        // A remote row from 历史记录 needs the shared site table first, exactly
        // like 在线阅读模式 does before it loads.
        if (mode.source == ReaderMode.Source.REMOTE) {
            ensureSiteConfig { load(mode, pick.path, pick.chapterIndex, pick.lineInChapter) }
            return
        }

        if (mode.split == ReaderMode.Split.CHAPTERS && mode.source == ReaderMode.Source.LOCAL_FILE) {
            startLocalChapters(mode, pick)
        } else {
            load(mode, pick.path, pick.chapterIndex, pick.lineInChapter)
        }
    }

    /**
     * 选书：先选目录，再从目录的文件列表里挑一本（对话框里也能从历史记录挑）。
     *
     * The dialog lists every readable file of the directory and remembers the
     * directory in [BookLibrary] (shared by all windows); 浏览… inside it falls
     * back to the system single-file dialog for a book stored elsewhere.
     */
    private fun chooseLocalText(mode: ReaderMode, onChosen: (PickedBook) -> Unit) {
        pickBook("${mode.title} — 选择书籍", TEXT_EXTENSIONS, onChosen)
    }

    private fun chooseEpub(onChosen: (PickedBook) -> Unit) {
        pickBook("选择 Epub 书籍", EPUB_EXTENSIONS, onChosen)
    }

    private fun pickBook(title: String, extensions: Set<String>, onChosen: (PickedBook) -> Unit) {
        // First run (no remembered directory yet): ask for the directory up
        // front — that is exactly the "选择目录后自动列出文件" flow.
        val directory = initialBookDirectory() ?: chooseBookDirectory() ?: return

        val dialog = BookPickerDialog(
            project = project,
            dialogTitle = title,
            extensions = extensions,
            startDirectory = directory,
            browse = { browseSingleFile(title, extensions) },
        )
        dialog.show()

        val pick = dialog.chosen ?: return
        pick.file?.let {
            BookLibrary.instance().rememberDirectory(it.parentFile?.absolutePath ?: directory.absolutePath)
        }
        onChosen(pick)
    }

    /** System "one file" dialog — the 浏览… escape hatch inside the picker. */
    private fun browseSingleFile(title: String, extensions: Set<String>): File? {
        val descriptor = singleFileDescriptor(title)
            .withFileFilter { file -> !file.isDirectory && file.extension?.lowercase() in extensions }

        val chosen = FileChooser.chooseFile(descriptor, project, lastDirectory()) ?: return null
        return File(chosen.path).takeIf { it.isFile }
    }

    /** Ask for a book directory (folder-only dialog) and remember it. */
    private fun chooseBookDirectory(): File? {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("选择书籍目录")
        val chosen = FileChooser.chooseFile(descriptor, project, lastDirectory()) ?: return null
        val directory = File(chosen.path)
        if (!directory.isDirectory) return null

        BookLibrary.instance().rememberDirectory(directory.absolutePath)
        return directory
    }

    /**
     * Where the picker starts: the shared directory first (a new window opens on
     * the same folder), then this project's last book folder, then the project root.
     */
    private fun initialBookDirectory(): File? {
        val shared = BookLibrary.instance().bookDirectory
        if (shared.isNotBlank()) {
            File(shared).takeIf { it.isDirectory }?.let { return it }
        }
        val path = ReadingState.getInstance(project).path
        if (path.isNotBlank() && !path.startsWith("http")) {
            File(path).parentFile?.takeIf { it.isDirectory }?.let { return it }
        }
        return project.basePath?.let { File(it) }?.takeIf { it.isDirectory }
    }

    /**
     * A "choose one file" descriptor, built without the deprecated factory methods.
     *
     * `FileChooserDescriptorFactory.createSingleFileDescriptor()` and its replacement
     * `createSingleLocalFileDescriptor()` are both `@Deprecated` on current platforms
     * (the latter wrongly allows directories), while the replacement the javadoc names,
     * `singleFile()`, only appeared in 2025 and so is missing from 2023.2–2024.x. The public
     * constructor is the common denominator of every supported version: 2023.2 has it, current
     * platforms only mark it `@ApiStatus.Obsolete`, and the verifier reports `@Deprecated` —
     * not Obsolete (it ignores that annotation).
     *
     * The flags are exactly `singleFile()`'s: one file, no folders, no jars, single selection.
     * Any narrower filtering is done by the caller's `withFileFilter`, as before.
     */
    private fun singleFileDescriptor(title: String): FileChooserDescriptor =
        // (chooseFiles, chooseFolders, chooseJars, chooseJarsAsFiles, chooseJarContents, chooseMultiple)
        FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(title)

    /** Start a system dialog where the last book was, so the user is not re-navigating. */
    private fun lastDirectory(): VirtualFile? {
        val shared = BookLibrary.instance().bookDirectory
        if (shared.isNotBlank()) {
            File(shared).takeIf { it.isDirectory }?.let {
                return LocalFileSystem.getInstance().refreshAndFindFileByPath(it.absolutePath)
            }
        }
        val path = ReadingState.getInstance(project).path
        if (path.isBlank() || path.startsWith("http")) return null
        val dir = File(path).parentFile ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Read"

        private val TEXT_EXTENSIONS = setOf("txt", "text", "md", "markdown", "log", "html", "htm")

        private val EPUB_EXTENSIONS = setOf("epub")

        /** Everything 打开书籍 can read — the mode combo decides how. */
        private val BOOK_EXTENSIONS = TEXT_EXTENSIONS + EPUB_EXTENSIONS

        fun getInstance(project: Project): ReaderController =
            project.getService(ReaderController::class.java)
    }
}
