package com.bookreader.reader

import com.bookreader.reader.loader.BookLoader
import com.bookreader.reader.ui.ReadPanelHolder
import com.bookreader.reader.ui.ReaderStatusBar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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

        val settings = ReaderSettings.instance()
        when (mode.source) {
            ReaderMode.Source.REMOTE -> startRemote(mode, settings)
            ReaderMode.Source.LOCAL_FILE -> {
                if (mode.split == ReaderMode.Split.CHAPTERS) {
                    chooseLocalText(mode) { file -> startLocalChapters(mode, file) }
                } else {
                    chooseLocalText(mode) { file -> load(mode, file.absolutePath) }
                }
            }
            ReaderMode.Source.EPUB -> chooseEpub { file -> load(mode, file.absolutePath) }
        }
    }

    private fun startRemote(mode: ReaderMode, settings: ReaderSettings) {
        val state = ReadingState.getInstance(project)
        val initial = state.path.takeIf { it.startsWith("http") }.orEmpty()

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

    private fun startLocalChapters(mode: ReaderMode, file: File) {
        val settings = ReaderSettings.instance()
        val regex = Messages.showInputDialog(
            project,
            "请输入【章节标题】正则（默认 ${ReaderSettings.DEFAULT_CHAPTER_REGEX}）：\n" +
                "示例：【第.*章.*】匹配【第1238章 标题】；加上 ^ 可只识别行首。",
            mode.title,
            Messages.getQuestionIcon(),
            settings.chapterRegex,
            null,
        )?.trim()
        if (regex.isNullOrEmpty()) return

        settings.chapterRegex = regex
        load(mode, file.absolutePath)
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
        ReadingState.getInstance(project).addHistory(
            HistoryEntry(
                title = book.title,
                modeName = book.mode.name,
                path = source,
                chapterCount = book.chapters.size,
                lineCount = book.lines.size,
                position = engine.lineIndex,
                visitedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Re-open an entry from 历史记录. */
    fun openHistory(entry: HistoryEntry) {
        val mode = ReaderMode.byNameOrNull(entry.modeName) ?: ReaderMode.REMOTE
        load(mode, entry.path, startLine = entry.position)
    }

    // ---- display control ---------------------------------------------------

    /** 上一页. */
    fun previousPage(): Boolean {
        val moved = engine.previous()
        refresh()
        return moved
    }

    /** 下一页. */
    fun nextPage(): Boolean {
        val moved = engine.next()
        refresh()
        return moved
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

        // The source is a URL only for the online modes; local/epub books are
        // identified by their path, and the 简易 modes have no chapter list at all.
        val source = book.chapters.firstOrNull()?.url?.takeIf { it.isNotBlank() }
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
        val source = book?.chapters?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
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

    /** 切换至本项目 — activate the Read tool window. */
    fun activateReadWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null, true)
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
     * Restore the book saved by [ReadingState] after the project opens.
     *
     * Only local sources are restored automatically: re-fetching a novel from a
     * website on every project open would be rude to the site and slow to boot.
     */
    fun restoreLast() {
        val state = ReadingState.getInstance(project)
        val path = state.path
        if (path.isBlank()) return
        val mode = ReaderMode.byNameOrNull(state.modeName) ?: return
        if (mode.source == ReaderMode.Source.REMOTE) return
        if (!File(path).isFile) return

        load(mode, path, state.chapterIndex, state.lineInChapter)
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

    private fun chooseLocalText(mode: ReaderMode, onChosen: (File) -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("${mode.title} — 选择小说文件")
            .withFileFilter { file -> !file.isDirectory && file.extension?.lowercase() in TEXT_EXTENSIONS }

        val chosen = FileChooser.chooseFile(descriptor, project, lastDirectory()) ?: return
        val file = File(chosen.path)
        if (!file.isFile) return
        onChosen(file)
    }

    private fun chooseEpub(onChosen: (File) -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("选择 Epub 文件")
            .withFileFilter { file -> !file.isDirectory && file.extension?.lowercase() == "epub" }

        val chosen = FileChooser.chooseFile(descriptor, project, lastDirectory()) ?: return
        val file = File(chosen.path)
        if (file.isFile) onChosen(file)
    }

    /** Open the file dialog where the last book was, so the user is not re-navigating. */
    private fun lastDirectory(): VirtualFile? {
        val path = ReadingState.getInstance(project).path
        if (path.isBlank() || path.startsWith("http")) return null
        val dir = File(path).parentFile ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Read"

        private val TEXT_EXTENSIONS = setOf("txt", "text", "md", "markdown", "log", "html", "htm")

        fun getInstance(project: Project): ReaderController =
            project.getService(ReaderController::class.java)
    }
}
