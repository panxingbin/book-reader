package com.bookreader.reader.ui

import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderSettings
import com.bookreader.reader.ReadingEngine
import com.bookreader.reader.model.BookContent
import com.bookreader.reader.ui.dialogs.ChapterListDialog
import com.bookreader.reader.ui.dialogs.ContentFilterDialog
import com.bookreader.reader.ui.dialogs.FontDialog
import com.bookreader.reader.ui.dialogs.HistoryDialog
import com.bookreader.reader.ui.dialogs.JumpContentDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * 全章节阅读模式 — the reading surface in the **Read** tool window.
 *
 * Less discreet than the status bar, but comfortable: real font control, a whole
 * page at a time, chapter jumping and content filtering.
 */
class ReadPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val engine: ReadingEngine get() = project.getService(ReadingEngine::class.java)
    private val controller: ReaderController get() = ReaderController.getInstance(project)

    private val headerLabel = JBLabel("还没有开始阅读").apply {
        border = JBUI.Borders.empty(4, 8)
        font = font.deriveFont(Font.BOLD)
    }

    private val infoLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(2, 8)
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8, 10)
        background = JBColor.background()
        foreground = JBColor.foreground()
    }

    private val emptyLabel = JBLabel(
        "<html><div style='text-align:center;'>该窗口仅【全章节】阅读才可使用，适合隐蔽性要求不高使用<br><br>" +
            "使用方式：<br>" +
            "1. 请选择【在线全章节】或【本地全章节】的阅读模式<br>" +
            "2. 解析完毕后，使用【上/下一页】快捷键翻页<br><br>" +
            "<i>菜单：Tools → Read</i></div></html>",
        SwingConstants.CENTER,
    )

    private val clearedLabel = JBLabel(
        "<html><div style='text-align:center;'>已隐藏阅读内容<br><br>" +
            "按 <b>Ctrl+Alt+B</b>（有人来了就按我）恢复阅读，<br>" +
            "或菜单 Tools → Read → 重新初始化 重新加载<br>" +
            "<i>阅读位置已保留，恢复即可继续</i></div></html>",
        SwingConstants.CENTER,
    )

    init {
        // Must be focusable: the platform decides "is this tool window active?"
        // from the focused component, and the stripe icon only hides a window
        // that is active. A plain JPanel is not focusable, and in the empty state
        // it contains nothing focusable either — so the Read window could never
        // become active and clicking its stripe icon a second time did nothing.
        isFocusable = true

        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)

        engine.addListener { refresh() }
        refresh()
    }

    private fun buildHeader(): JPanel {
        val top = JPanel(BorderLayout())
        top.add(headerLabel, BorderLayout.NORTH)
        top.add(infoLabel, BorderLayout.CENTER)

        // The 功能菜单 now lives on the tool window's title row (gear icon,
        // see ReadToolWindowFactory.setTitleActions); only the quick 清屏 button
        // stays in the panel.
        val menuRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(
                JButton("选择书籍", AllIcons.Actions.MenuOpen).apply {
                    toolTipText = "从书籍目录中选择一本书（Ctrl+Alt+O）"
                    isFocusable = false
                    addActionListener { controller.openBookPicker() }
                },
            )
            add(
                JButton("隐藏阅读内容", AllIcons.General.HideToolWindow).apply {
                    toolTipText = "一键清屏（Ctrl+Alt+B 再按可恢复）"
                    isFocusable = false
                    addActionListener {
                        if (controller.hidden) controller.showReading() else controller.clearScreen()
                    }
                },
            )
        }

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
        bar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        bar.add(button("上一页", AllIcons.Actions.Back) { controller.previousPage() })
        bar.add(button("下一页", AllIcons.Actions.Forward) { controller.nextPage() })
        bar.add(button("章节跳转", AllIcons.Actions.ListFiles) { ChapterListDialog(project).show() })
        bar.add(button("内容跳转", AllIcons.Actions.Find) { JumpContentDialog(project).show() })
        bar.add(button("字体选择", AllIcons.General.Settings) { FontDialog(project).show() })
        bar.add(button("内容过滤", AllIcons.General.Filter) { ContentFilterDialog(project).show() })
        bar.add(button("历史记录", AllIcons.Vcs.History) { HistoryDialog(project).show() })

        val head = JPanel(BorderLayout())
        head.add(top, BorderLayout.NORTH)
        head.add(menuRow, BorderLayout.CENTER)
        head.add(bar, BorderLayout.SOUTH)
        return head
    }

    private fun buildBody(): JPanel {
        val scroll = JBScrollPane(textArea)
        val empty = JPanel(BorderLayout()).apply {
            add(emptyLabel, BorderLayout.CENTER)
            emptyLabel.border = JBUI.Borders.empty(24, 12)
        }
        val cleared = JPanel(BorderLayout()).apply {
            add(clearedLabel, BorderLayout.CENTER)
            clearedLabel.border = JBUI.Borders.empty(24, 12)
        }
        val cards = java.awt.CardLayout()
        val holder = JPanel(cards)
        holder.add(scroll, CARD_TEXT)
        holder.add(empty, CARD_EMPTY)
        holder.add(cleared, CARD_CLEARED)
        body = holder
        bodyCards = cards
        return holder
    }

    private lateinit var body: JPanel
    private lateinit var bodyCards: java.awt.CardLayout

    /** Re-read the engine and repaint. */
    fun refresh() {
        if (!this::body.isInitialized) return

        val book: BookContent? = engine.book
        if (book == null) {
            headerLabel.text = "还没有开始阅读"
            infoLabel.text = "菜单：Tools → Read"
            bodyCards.show(body, CARD_EMPTY)
            return
        }

        // 快速清屏 state: keep the engine position, but show only the instruction
        // so the window itself is not the thing that gives the reader away.
        if (controller.hidden) {
            headerLabel.text = "已隐藏"
            infoLabel.text = ""
            bodyCards.show(body, CARD_CLEARED)
            return
        }

        val chapter = engine.currentChapter()
        val chapterTitle = if (chapter in book.chapters.indices) book.chapters[chapter].title else book.title
        headerLabel.text = if (book.mode.isFullChapter) chapterTitle else "${book.title} · $chapterTitle"

        infoLabel.text = buildString {
            append(book.mode.title)
            append("  ·  ")
            if (book.chapters.isNotEmpty()) append("第 ${chapter + 1}/${book.chapters.size} 章  ·  ")
            append("${engine.lineIndex + 1}/${book.lines.size} 行  ·  ")
            append("${engine.progressPercent()}%")
            if (engine.isAutoNext) append("  ·  自动翻页中")
            if (!book.wholeBook) append("  ·  按需加载")
        }

        applyFont()
        textArea.text = engine.pageLines().joinToString("\n")
        textArea.caretPosition = 0
        bodyCards.show(body, CARD_TEXT)
    }

    /** Apply 字体选择 to the reading surface. */
    private fun applyFont() {
        val settings = ReaderSettings.instance()
        val family = settings.fontFamily.ifBlank { Font.MONOSPACED }
        textArea.font = Font(family, Font.PLAIN, settings.fontSize.coerceIn(8, 72))

        val hex = settings.fontColorHex.trim().removePrefix("#")
        textArea.foreground = runCatching {
            if (hex.length == 6) Color(hex.toInt(16)) else null
        }.getOrNull() ?: JBColor.foreground()
    }

    private fun button(text: String, icon: javax.swing.Icon?, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            toolTipText = text
            isFocusable = false
            addActionListener { action() }
        }

    private companion object {
        const val CARD_TEXT = "text"
        const val CARD_EMPTY = "empty"
        const val CARD_CLEARED = "cleared"
    }
}

/**
 * The panel lives inside a tool window `Content`, which is created lazily, so
 * actions need a way to reach it without forcing creation.
 */
object ReadPanelHolder {

    private val panels = java.util.concurrent.ConcurrentHashMap<Project, ReadPanel>()

    fun set(project: Project, panel: ReadPanel) {
        panels[project] = panel
    }

    fun get(project: Project): ReadPanel? = panels[project]
}
