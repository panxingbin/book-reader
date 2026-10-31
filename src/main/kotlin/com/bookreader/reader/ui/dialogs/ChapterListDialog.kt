package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReadingEngine
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 章节列表 / 跳转 — pick a chapter and jump to it.
 *
 * The list is filtered as the user types, which matters for a 3000-chapter web
 * novel where scrolling is hopeless. The chapter the reader is currently inside
 * is marked with ▶ and pre-selected (and scrolled into view) when the dialog
 * opens; with an empty filter the selection stays on it.
 */
class ChapterListDialog(private val project: Project) : DialogWrapper(project) {

    private val engine: ReadingEngine get() = project.getService(ReadingEngine::class.java)

    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val filterField = JBTextField()
    private val countLabel = JLabel()

    private val currentChapter: Int = engine.currentChapter()

    private val allRows: List<Row> = engine.chapters.map {
        Row(it.index, (if (it.index == currentChapter) "▶ " else "") + "${it.number}. ${it.title}")
    }

    init {
        title = "章节列表 / 跳转"
        init()
        reload("")
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) doOKAction()
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 18

        filterField.emptyText.text = "输入章节名筛选…"
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = reload(filterField.text)
            override fun removeUpdate(e: DocumentEvent) = reload(filterField.text)
            override fun changedUpdate(e: DocumentEvent) = reload(filterField.text)
        })

        val panel = JPanel(BorderLayout(0, 4))
        panel.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(420))
        panel.add(filterField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(countLabel, BorderLayout.SOUTH)
        return panel
    }

    private fun reload(query: String) {
        val q = query.trim()
        model.clear()
        val matches = if (q.isEmpty()) allRows else allRows.filter { it.text.contains(q, ignoreCase = true) }
        for (row in matches) model.addElement(row)
        countLabel.text = "共 ${allRows.size} 章，匹配 ${matches.size} 章"
        if (model.size() > 0) {
            // No filter: keep the selection on the chapter being read (and show
            // it — a 3000-chapter list starts far away from it). A filter starts
            // on the first match, as before.
            val select = if (q.isEmpty()) {
                (0 until model.size()).firstOrNull { model[it].index == currentChapter } ?: 0
            } else {
                0
            }
            list.selectedIndex = select
            list.ensureIndexIsVisible(select)
        }
    }

    override fun doOKAction() {
        val row = list.selectedValue ?: return
        engine.goToChapter(row.index)
        super.doOKAction()
    }

    private data class Row(val index: Int, val text: String) {
        override fun toString(): String = text
    }
}
