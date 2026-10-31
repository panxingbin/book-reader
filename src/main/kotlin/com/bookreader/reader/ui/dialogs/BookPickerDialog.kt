package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.BookLibrary
import com.bookreader.reader.HistoryEntry
import com.bookreader.reader.ReaderMode
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel

/**
 * What [BookPickerDialog] returns: either a book from the directory or a row of
 * 历史记录.
 */
data class PickedBook(
    /** Local file path, or the online catalog URL of a remote history row. */
    val path: String,
    /** The local file, or `null` for a remote (URL) history row. */
    val file: File?,
    /** Mode recorded in the history row; `null` = use the caller's fixed mode. */
    val mode: ReaderMode?,
    val chapterIndex: Int,
    val lineInChapter: Int,
    /**
     * `true` when the pick came from a 历史记录 row — the book has been read
     * before, so the chapter-regex prompt is skipped and it opens right away.
     */
    val fromHistory: Boolean = false,
)

/**
 * 选择书籍 — pick a book from the directory **or** from 历史记录.
 *
 * The old flow opened the system file dialog for a single file every time, and
 * nothing was remembered between windows. Here the directory is listed once and
 * **all** readable files in it are shown, with sub-directories navigable
 * (double-click enters, 上级目录 goes back). The chosen directory is stored in
 * [BookLibrary], i.e. shared by every IDE window, so the next window opens on
 * the same folder.
 *
 * A second tab lists 历史记录 (also shared through [BookLibrary]), so a book that
 * was read before can be resumed from the same dialog without going through the
 * 历史记录 menu — and without losing the reading position: a history row carries
 * the chapter + offset it was left at.
 *
 * 浏览… keeps the old system file dialog as an escape hatch, for a book that
 * lives outside the remembered directory.
 *
 * @param extensions lowercase file extensions that can actually be read in the
 *   mode this dialog was opened for (txt set for the text modes, epub for the
 *   epub modes) — other files are hidden instead of failing after a load.
 * @param browse the system "choose one file" fallback, supplied by
 *   [com.bookreader.reader.ReaderController].
 * @param modes reading modes offered by the 阅读方式 combo. Empty when the caller
 *   already fixed the mode (each 本地阅读 menu entry does), in which case no combo
 *   is shown and [PickedBook.mode] stays null.
 */
class BookPickerDialog(
    private val project: Project,
    dialogTitle: String,
    private val extensions: Set<String>,
    startDirectory: File,
    private val browse: () -> File?,
    private val modes: List<ReaderMode> = emptyList(),
    initialMode: ReaderMode? = null,
) : DialogWrapper(project) {

    /** The picked book, or `null` when the dialog was cancelled. */
    var chosen: PickedBook? = null
        private set

    private var directory: File = startDirectory
    private var pendingInitialMode: ReaderMode? = initialMode

    // ---- 目录书籍 tab ------------------------------------------------------

    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val pathField = JBTextField()
    private val countLabel = JBLabel()

    // ---- 历史记录 tab ------------------------------------------------------

    private val historyModel = DefaultListModel<HistoryEntry>()
    private val historyList = JBList(historyModel)
    private val historyCountLabel = JBLabel()

    private val tabs = JTabbedPane()

    private val modeCombo = JComboBox<ReaderMode>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, selected, focus)
                if (label is JLabel) {
                    label.text = (value as? ReaderMode)?.title ?: ""
                }
                return label
            }
        }
    }

    init {
        title = dialogTitle
        init()
        reload()

        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) doOKAction()
            }
        })
        historyList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) doOKAction()
            }
        })

        list.addListSelectionListener { syncModeChoices() }
        syncModeChoices()
    }

    override fun createCenterPanel(): JComponent {
        tabs.addTab("目录书籍", buildDirectoryTab())
        tabs.addTab("历史记录", buildHistoryTab())

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(460))
        if (modes.isNotEmpty()) {
            panel.add(
                JPanel(BorderLayout(6, 0)).apply {
                    add(JBLabel("阅读方式："), BorderLayout.WEST)
                    add(modeCombo, BorderLayout.CENTER)
                },
                BorderLayout.NORTH,
            )
        }
        panel.add(tabs, BorderLayout.CENTER)
        return panel
    }

    private fun buildDirectoryTab(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 14
        list.cellRenderer = Renderer()
        list.emptyText.text = "该目录下没有可读的 ${extensions.joinToString(" / ")} 文件"

        pathField.apply {
            toolTipText = "当前书籍目录，可直接粘贴路径后按 Enter 跳转"
            addActionListener { navigateToTypedPath() }
        }

        val top = JPanel(BorderLayout(6, 0))
        top.add(pathField, BorderLayout.CENTER)
        top.add(
            JButton("选择目录…").apply {
                toolTipText = "选择一个书籍目录，自动列出目录下所有可读文件"
                addActionListener { pickDirectory() }
            },
            BorderLayout.EAST,
        )

        val tools = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton("上级目录").apply {
                toolTipText = "返回上一级目录"
                addActionListener { directory.parentFile?.let { navigate(it) } }
            })
            add(JButton("刷新").apply {
                toolTipText = "重新读取当前目录"
                addActionListener { reload() }
            })
            add(JButton("浏览…").apply {
                toolTipText = "改用系统文件选择框，只选一个文件"
                addActionListener { browse()?.let { acceptFile(it) } }
            })
        }

        countLabel.foreground = JBColor.GRAY

        val bottom = JPanel(BorderLayout(0, 4))
        bottom.add(tools, BorderLayout.WEST)
        bottom.add(countLabel, BorderLayout.EAST)

        val tab = JPanel(BorderLayout(0, 6))
        tab.add(top, BorderLayout.NORTH)
        tab.add(JBScrollPane(list), BorderLayout.CENTER)
        tab.add(bottom, BorderLayout.SOUTH)
        return tab
    }

    private fun buildHistoryTab(): JComponent {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.visibleRowCount = 14
        historyList.cellRenderer = HistoryRenderer()
        historyList.emptyText.text = "还没有阅读记录 —— 读过的书会出现在这里，所有窗口共享"

        historyCountLabel.foreground = JBColor.GRAY

        val tab = JPanel(BorderLayout(0, 4))
        tab.add(JBScrollPane(historyList), BorderLayout.CENTER)
        tab.add(historyCountLabel, BorderLayout.SOUTH)
        return tab
    }

    // ---- directory tab -----------------------------------------------------

    /** Re-read [directory]: sub-directories first, then the readable files. */
    private fun reload() {
        pathField.text = directory.absolutePath
        model.clear()

        val entries = directory.listFiles()?.filterNot { it.name.startsWith(".") }.orEmpty()
        val folders = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val books = entries
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in extensions }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        for (folder in folders) model.addElement(Row(folder, isDirectory = true))
        for (book in books) model.addElement(Row(book, isDirectory = false))

        countLabel.text = "共 ${folders.size} 个目录 · ${books.size} 个可读文件"

        // Pre-select the first book so Enter / 打开 works without an extra click.
        val firstBook = (0 until model.size()).firstOrNull { !model.get(it).isDirectory }
        if (firstBook != null) list.selectedIndex = firstBook
        syncModeChoices()

        reloadHistory()
    }

    /** Re-read 历史记录, keeping only the rows this dialog can actually open. */
    private fun reloadHistory() {
        historyModel.clear()
        val entries = BookLibrary.instance().history().filter { historyCompatible(it) }
        for (entry in entries) historyModel.addElement(entry)

        historyCountLabel.text = "共 ${entries.size} 条记录（所有窗口共享，双击恢复）"
        if (historyModel.size() > 0) historyList.selectedIndex = 0
    }

    /**
     * `true` when a history row can be opened from this dialog: the generic
     * 打开书籍 dialog takes everything, a mode-fixed dialog only takes rows of
     * the same kind of source (epub rows for the epub modes, text rows for the
     * text modes).
     */
    private fun historyCompatible(entry: HistoryEntry): Boolean {
        val mode = ReaderMode.byNameOrNull(entry.modeName) ?: return false
        if (modes.isNotEmpty()) return true
        if (mode.source == ReaderMode.Source.REMOTE) return false
        if (mode.source == ReaderMode.Source.EPUB) return "epub" in extensions

        return entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
    }

    private fun navigate(target: File) {
        if (!target.isDirectory) return
        directory = target
        reload()
    }

    private fun navigateToTypedPath() {
        val typed = File(pathField.text.trim())
        if (typed.isDirectory) navigate(typed) else pathField.text = directory.absolutePath
    }

    private fun pickDirectory() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("选择书籍目录")
        val current = LocalFileSystem.getInstance().refreshAndFindFileByPath(directory.absolutePath)
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        val picked = File(chosen.path)
        if (!picked.isDirectory) return

        BookLibrary.instance().rememberDirectory(picked.absolutePath)
        navigate(picked)
    }

    // ---- accepting ---------------------------------------------------------

    override fun doOKAction() {
        if (tabs.selectedIndex == HISTORY_TAB) {
            val entry = historyList.selectedValue ?: return
            acceptHistory(entry)
            return
        }

        val row = list.selectedValue ?: return
        if (row.isDirectory) {
            // Enter / double-click on a folder walks into it instead of closing.
            navigate(row.file)
            return
        }
        acceptFile(row.file)
    }

    /** Open [file] and close with OK — used by the list and by the 浏览 fallback. */
    private fun acceptFile(file: File) {
        BookLibrary.instance().rememberDirectory(file.parentFile?.absolutePath ?: directory.absolutePath)
        chosen = PickedBook(
            path = file.absolutePath,
            file = file,
            mode = if (modes.isNotEmpty()) compatibleModeFor(file) else null,
            chapterIndex = 0,
            lineInChapter = 0,
        )
        super.doOKAction()
    }

    /** Resume a 历史记录 row — keeps the mode and the chapter/offset it was left at. */
    private fun acceptHistory(entry: HistoryEntry) {
        val remote = entry.path.startsWith("http://", true) || entry.path.startsWith("https://", true)
        val file = if (remote) null else File(entry.path)
        if (file != null) {
            BookLibrary.instance().rememberDirectory(file.parentFile?.absolutePath ?: directory.absolutePath)
        }

        chosen = PickedBook(
            path = entry.path,
            file = file,
            mode = if (modes.isNotEmpty()) ReaderMode.byNameOrNull(entry.modeName) else null,
            chapterIndex = entry.chapterIndex,
            lineInChapter = entry.lineInChapter,
            fromHistory = true,
        )
        super.doOKAction()
    }

    /**
     * Keep the 阅读方式 combo limited to the modes that can read the selected
     * file: epub modes for `.epub`, the text modes for everything else.
     */
    private fun syncModeChoices() {
        if (modes.isEmpty()) return

        val file = list.selectedValue?.takeIf { !it.isDirectory }?.file
        val compatible = compatibleModes(file)
        if (compatible.isEmpty()) return

        val previous = modeCombo.selectedItem as? ReaderMode
        val target = previous?.takeIf { it in compatible }
            ?: pendingInitialMode?.takeIf { it in compatible }
            ?: compatible.first()

        modeCombo.removeAllItems()
        for (mode in compatible) modeCombo.addItem(mode)
        modeCombo.selectedItem = target
        pendingInitialMode = null
    }

    private fun compatibleModes(file: File?): List<ReaderMode> {
        if (modes.isEmpty()) return emptyList()
        if (file == null) return modes

        val epub = file.extension.equals("epub", ignoreCase = true)
        val compatible = modes.filter { (it.source == ReaderMode.Source.EPUB) == epub }
        return compatible.ifEmpty { modes }
    }

    private fun compatibleModeFor(file: File): ReaderMode? {
        val compatible = compatibleModes(file)
        return (modeCombo.selectedItem as? ReaderMode)?.takeIf { it in compatible }
            ?: compatible.firstOrNull()
    }

    private data class Row(val file: File, val isDirectory: Boolean)

    private inner class Renderer : DefaultListCellRenderer() {
        private val format = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            focus: Boolean,
        ): Component {
            // `super` returns **this** instance (a DefaultListCellRenderer, i.e. a
            // plain JLabel) — casting it to JBLabel throws ClassCastException, and
            // the platform wraps renderers (ExpandedItemListCellRendererWrapper),
            // so configure `this` directly instead.
            super.getListCellRendererComponent(list, value, index, selected, focus)
            val row = value as? Row ?: return this
            border = JBUI.Borders.empty(3, 6)

            if (row.isDirectory) {
                icon = AllIcons.Nodes.Folder
                text = "${row.file.name}/"
                return this
            }

            icon = AllIcons.FileTypes.Text
            text = "<html>${escape(row.file.name)} " +
                "<font color='gray'>· ${humanSize(row.file.length())} · " +
                "${format.format(Date(row.file.lastModified()))}</font></html>"
            return this
        }
    }

    /** 历史记录 row: title, mode, where it stopped, when, and the path. */
    private inner class HistoryRenderer : DefaultListCellRenderer() {
        private val format = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            focus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, selected, focus)
            val entry = value as? HistoryEntry ?: return this

            val mode = ReaderMode.byNameOrNull(entry.modeName)?.title ?: entry.modeName
            text = "<html><b>${escape(entry.title)}</b> " +
                "<font color='gray'>· $mode · 读到 ${entry.position + 1} 行 · " +
                "${format.format(Date(entry.visitedAt))}</font><br>" +
                "<font color='gray'>${escape(entry.path)}</font></html>"
            border = JBUI.Borders.empty(3, 6)
            return this
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val HISTORY_TAB = 1
    }
}
