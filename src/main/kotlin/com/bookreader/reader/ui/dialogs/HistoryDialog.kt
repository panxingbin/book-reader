package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.BookLibrary
import com.bookreader.reader.HistoryEntry
import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 历史记录 — every book read recently, newest first.
 *
 * Backed by the application-level [BookLibrary], so the list is the same in
 * every IDE window (it used to be per project, which is why a new window showed
 * an empty history). A row carries enough to resume without re-parsing: the
 * mode, the source, and how big the book was when it was last opened.
 * Double-click resumes it.
 */
class HistoryDialog(private val project: Project) : DialogWrapper(project) {

    private val library = BookLibrary.instance()
    private val model = DefaultListModel<Any>()
    private val list = JBList(model)

    init {
        title = "历史记录"
        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.visibleRowCount = 14
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) openSelected()
            }
        })

        val buttons = JPanel()
        buttons.add(JButton("打开").apply { addActionListener { openSelected() } })
        buttons.add(JButton("删除").apply { addActionListener { removeSelected() } })
        buttons.add(JButton("清空").apply {
            addActionListener {
                if (confirmClear()) {
                    library.clearHistory()
                    reload()
                }
            }
        })

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(540), JBUI.scale(360))
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private fun reload() {
        model.clear()
        val history = library.history()
        if (history.isEmpty()) {
            model.addElement(EMPTY_HINT)
        } else {
            for (entry in history) model.addElement(entry)
        }
        list.selectedIndex = if (history.isEmpty()) -1 else 0
    }

    private fun selectedEntry(): HistoryEntry? = list.selectedValue as? HistoryEntry

    private fun openSelected() {
        val entry = selectedEntry() ?: return
        close(OK_EXIT_CODE)
        ReaderController.getInstance(project).openHistory(entry)
    }

    private fun removeSelected() {
        val entry = selectedEntry() ?: return
        // 删除 / 清空都不可撤销，先确认再动手。
        val answer = Messages.showYesNoDialog(
            project,
            "删除历史记录「${entry.title}」？",
            "历史记录",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        library.removeHistory(entry)
        reload()
    }

    private fun confirmClear(): Boolean =
        Messages.showYesNoDialog(
            project,
            "清空全部历史记录？该操作无法撤销。",
            "历史记录",
            Messages.getWarningIcon(),
        ) == Messages.YES

    private class Renderer : DefaultListCellRenderer() {
        private val format = SimpleDateFormat("MM-dd HH:mm")

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            focus: Boolean,
        ): Component {
            // `super` returns **this** instance (a DefaultListCellRenderer, i.e. a
            // plain JLabel); casting it to JBLabel throws ClassCastException, so
            // configure `this` directly.
            super.getListCellRendererComponent(list, value, index, selected, focus)
            val entry = value as? HistoryEntry
            if (entry == null) {
                text = "还没有阅读记录。菜单：Tools → Read（历史记录在所有窗口中共享）"
                border = JBUI.Borders.empty(6)
                return this
            }
            val mode = ReaderMode.byNameOrNull(entry.modeName)?.title ?: entry.modeName
            text = "<html><b>${escape(entry.title)}</b><br>" +
                "<font color='gray'>$mode · 章节数【${entry.chapterCount}】 · 内容行数【${entry.lineCount}】 · " +
                "读到 ${entry.position + 1} 行 · ${format.format(Date(entry.visitedAt))}</font><br>" +
                "<font color='gray'>${escape(entry.path)}</font></html>"
            border = JBUI.Borders.empty(4, 6)
            return this
        }

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private companion object {
        val EMPTY_HINT = Any()
    }
}
