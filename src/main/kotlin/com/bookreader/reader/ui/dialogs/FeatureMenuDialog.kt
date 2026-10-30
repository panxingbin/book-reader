package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ui.ReaderActionsCatalog
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 功能菜单 — the whole plugin as one searchable list.
 *
 * Opened from the gear on the **Read** tool window's title row. It used to be a
 * `JBPopup` anchored under the gear; it is now a normal modal dialog, matching
 * the 选书 dialog: one list, a filter field, double-click / OK to run, Cancel to
 * close. That also removes the popup-vs-tool-window focus dance the old toggle
 * needed — a modal dialog simply cannot be re-clicked from behind.
 *
 * The rows are not a second implementation of the menu: each one looks its
 * action up in `ActionManager` by the id registered in `plugin.xml` and runs it
 * through the platform's own trigger path, exactly like the real menu.
 *
 * @param contextComponent component the action system resolves each action's
 *   `DataContext` from — the tool window, which stays attached after the dialog
 *   closes (the dialog itself is gone by then).
 */
class FeatureMenuDialog(
    project: Project,
    private val contextComponent: JComponent,
) : DialogWrapper(project) {

    private val model = DefaultListModel<Entry>()
    private val list = JBList(model)
    private val filterField = JBTextField()

    init {
        title = "功能菜单"
        init()
        reload("")

        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) doOKAction()
            }
        })

        // Group headers are decoration; keep the selection on real rows.
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val index = list.selectedIndex
            if (index < 0 || model.get(index) is Entry.Item) return@addListSelectionListener

            val next = (index + 1 until model.size()).firstOrNull { model.get(it) is Entry.Item }
                ?: (index - 1 downTo 0).firstOrNull { model.get(it) is Entry.Item }
            if (next != null) list.selectedIndex = next
        }
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.visibleRowCount = 18

        filterField.emptyText.text = "输入功能名筛选…"
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = reload(filterField.text)
            override fun removeUpdate(e: DocumentEvent) = reload(filterField.text)
            override fun changedUpdate(e: DocumentEvent) = reload(filterField.text)
        })

        val hint = JBLabel("双击执行，Esc 关闭").apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(2)
        }

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(520))
        panel.add(filterField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(hint, BorderLayout.SOUTH)
        return panel
    }

    /** Rebuild the list for [query], keeping the group headers of matching rows. */
    private fun reload(query: String) {
        val q = query.trim()
        model.clear()

        for (group in ReaderActionsCatalog.groups) {
            val matches = group.rows.filter {
                q.isEmpty() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
            if (matches.isEmpty()) continue

            model.addElement(Entry.Header(group.title))
            for (row in matches) model.addElement(Entry.Item(row))
        }

        val first = (0 until model.size()).firstOrNull { model.get(it) is Entry.Item }
        if (first != null) list.selectedIndex = first
    }

    override fun doOKAction() {
        val entry = list.selectedValue as? Entry.Item ?: return
        val action = ActionManager.getInstance().getAction(entry.row.actionId) ?: return

        super.doOKAction()

        // Run after the dialog is gone: several of these open their own dialog,
        // which must not be owned by a window that is being disposed.
        ApplicationManager.getApplication().invokeLater {
            ActionManager.getInstance()
                .tryToExecute(action, null, contextComponent, ActionPlaces.UNKNOWN, true)
        }
    }

    private sealed interface Entry {
        data class Header(val title: String) : Entry
        data class Item(val row: ReaderActionsCatalog.Row) : Entry
    }

    private class Renderer : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            focus: Boolean,
        ): Component {
            // `super` returns **this** instance (a plain JLabel); casting it to
            // JBLabel throws, so configure `this` directly.
            super.getListCellRendererComponent(list, value, index, selected, focus)
            val base = list?.font ?: font

            when (value) {
                is Entry.Header -> {
                    text = value.title
                    icon = null
                    font = base.deriveFont(Font.BOLD)
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(8, 8, 2, 8)
                }

                is Entry.Item -> {
                    val row = value.row
                    icon = row.icon
                    font = base.deriveFont(Font.PLAIN)
                    text = buildString {
                        append("<html><b>").append(escape(row.title)).append("</b>")
                        if (row.shortcut.isNotBlank()) {
                            append(" <font color='gray'>").append(escape(row.shortcut)).append("</font>")
                        }
                        append("<br><font color='gray'>").append(escape(row.description)).append("</font></html>")
                    }
                    border = JBUI.Borders.empty(3, 8)
                }

                else -> Unit
            }
            return this
        }

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
