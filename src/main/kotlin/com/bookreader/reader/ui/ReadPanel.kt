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
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * 全章节阅读模式 — the reading surface in the **Read** tool window.
 *
 * Less discreet than the status bar, but comfortable: real font control, a whole
 * page at a time, chapter jumping and content filtering. The reading area pages
 * with the keyboard (←/→, Space/Shift+Space, PgUp/PgDn) and the wheel — wheel
 * past the bottom turns the page, wheel above the top goes back.
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
            "按 <b>Ctrl+Alt+X</b>（有人来了就按我）恢复阅读，<br>" +
            "或菜单 Tools → Read → 重新初始化 重新加载<br>" +
            "<i>阅读位置已保留，恢复即可继续</i></div></html>",
        SwingConstants.CENTER,
    )

    /** The reading area's scroll pane, kept as a field for wheel paging / adaptive page size. */
    private lateinit var scroll: JBScrollPane

    /** A transient info row message (已经是最后一页了 / 正在加载下一章…), cleared by [transientTimer]. */
    @Volatile
    private var transientInfo: String? = null

    private var transientTimer: javax.swing.Timer? = null

    private var speedDownButton: JButton? = null
    private var speedUpButton: JButton? = null

    init {
        // Must be focusable: the platform decides "is this tool window active?"
        // from the focused component, and the stripe icon only hides a window
        // that is active. A plain JPanel is not focusable, and in the empty state
        // it contains nothing focusable either — so the Read window could never
        // become active and clicking its stripe icon a second time did nothing.
        isFocusable = true

        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)

        installPagingKeys()
        installWheelPaging()
        installAdaptivePageSize()

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
                    toolTipText = "一键清屏（Ctrl+Alt+X 再按可恢复）"
                    isFocusable = false
                    addActionListener {
                        if (controller.hidden) controller.showReading() else controller.clearScreen()
                    }
                },
            )
        }

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
        bar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        bar.add(button("上一页", AllIcons.Actions.Back) { page(forward = false) })
        bar.add(button("下一页", AllIcons.Actions.Forward) { page(forward = true) })
        bar.add(button("上一章", AllIcons.Actions.PreviousOccurence) { previousChapter() })
        bar.add(button("下一章", AllIcons.Actions.NextOccurence) { nextChapter() })
        bar.add(button("章节跳转", AllIcons.Actions.ListFiles) { ChapterListDialog(project).show() })
        bar.add(button("内容跳转", AllIcons.Actions.Find) { JumpContentDialog(project).show() })
        bar.add(button("字体选择", AllIcons.General.Settings) { FontDialog(project).show() })
        bar.add(button("内容过滤", AllIcons.General.Filter) { ContentFilterDialog(project).show() })
        bar.add(button("历史记录", AllIcons.Vcs.History) { HistoryDialog(project).show() })
        bar.add(
            JButton("速度 −").apply {
                isFocusable = false
                addActionListener { engine.adjustAutoNextSeconds(-1) }
                speedDownButton = this
            },
        )
        bar.add(
            JButton("速度 +").apply {
                isFocusable = false
                addActionListener { engine.adjustAutoNextSeconds(+1) }
                speedUpButton = this
            },
        )

        val head = JPanel(BorderLayout())
        head.add(top, BorderLayout.NORTH)
        head.add(menuRow, BorderLayout.CENTER)
        head.add(bar, BorderLayout.SOUTH)
        return head
    }

    private fun buildBody(): JPanel {
        scroll = JBScrollPane(textArea)
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
            updateSpeedTooltips()
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

        val state = buildString {
            append(book.mode.title)
            append("  ·  ")
            if (book.chapters.isNotEmpty()) append("第 ${chapter + 1}/${book.chapters.size} 章  ·  ")
            append("${engine.lineIndex + 1}/${book.lines.size} 行  ·  ")
            append("${engine.progressPercent()}%")
            if (engine.isAutoNext) {
                append("  ·  自动翻页中（${ReaderSettings.instance().autoNextSeconds} 秒）")
            }
            if (!book.wholeBook) append("  ·  按需加载")
        }
        infoLabel.text = transientInfo ?: state

        applyFont()
        updateAdaptiveLines()
        textArea.text = engine.pageLines().joinToString("\n")
        textArea.caretPosition = 0
        bodyCards.show(body, CARD_TEXT)
        updateSpeedTooltips()
    }

    // ---- paging ------------------------------------------------------------

    /** One page turn, with 读头/读尾/加载中 feedback in the info row. */
    private fun page(forward: Boolean) {
        if (forward) {
            when (controller.nextPage()) {
                ReadingEngine.PageTurn.END -> flashInfo("已经是最后一页了")
                ReadingEngine.PageTurn.LOADING -> flashInfo("正在加载下一章…")
                ReadingEngine.PageTurn.MOVED -> Unit
            }
        } else if (!controller.previousPage()) {
            flashInfo("已经是最前面了")
        }
    }

    private fun previousChapter() {
        if (!controller.previousChapter()) flashInfo("已经是第一章了")
    }

    private fun nextChapter() {
        if (!controller.nextChapter()) flashInfo("已经是最后一章了")
    }

    /** Show [text] in the info row for a moment, then fall back to the real state. */
    private fun flashInfo(text: String) {
        transientTimer?.stop()
        transientInfo = text
        refresh()
        transientTimer = javax.swing.Timer(INFO_FLASH_MS) {
            transientTimer = null
            transientInfo = null
            refresh()
        }.apply { isRepeats = false; start() }
    }

    private fun updateSpeedTooltips() {
        val seconds = ReaderSettings.instance().autoNextSeconds
        speedDownButton?.toolTipText = "自动翻页间隔 −1 秒（当前 $seconds 秒）"
        speedUpButton?.toolTipText = "自动翻页间隔 +1 秒（当前 $seconds 秒）"
    }

    // ---- paging input ------------------------------------------------------

    /**
     * Keyboard paging for the reading area: → / Space / PgDn forward,
     * ← / Shift+Space / PgUp back. Bound on the text area when it has focus and
     * on the panel while any child has it (without Space, so a focused button
     * still works with Space).
     */
    private fun installPagingKeys() {
        val forwardStrokes = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
        )
        val backStrokes = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.SHIFT_DOWN_MASK),
        )

        fun bind(map: JComponent, strokes: List<KeyStroke>, forward: Boolean) {
            val input = map.getInputMap(JComponent.WHEN_FOCUSED)
            val actions = map.actionMap
            for (stroke in strokes) {
                input.put(stroke, stroke)
                actions.put(stroke, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) = page(forward)
                })
            }
        }

        bind(textArea, forwardStrokes, forward = true)
        bind(textArea, backStrokes, forward = false)

        // No Space here: focus often sits on a toolbar button, where Space means click.
        val panelForward = forwardStrokes.filterNot { it.keyCode == KeyEvent.VK_SPACE }
        val panelBack = backStrokes.filterNot { it.keyCode == KeyEvent.VK_SPACE }
        val input = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actions = actionMap
        for (stroke in panelForward) {
            input.put(stroke, stroke)
            actions.put(stroke, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = page(forward = true)
            })
        }
        for (stroke in panelBack) {
            input.put(stroke, stroke)
            actions.put(stroke, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = page(forward = false)
            })
        }
    }

    /**
     * Wheel paging: wheel past the bottom of the page turns to the next page,
     * wheel above the top goes back. In between, the scroll pane scrolls as
     * usual (the event is left unconsumed for it).
     */
    private fun installWheelPaging() {
        textArea.addMouseWheelListener { event ->
            if (engine.book == null) return@addMouseWheelListener
            val bar = scroll.verticalScrollBar ?: return@addMouseWheelListener
            val atTop = bar.value <= 0
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum
            when {
                event.wheelRotation < 0 && atTop && engine.lineIndex > 0 -> {
                    event.consume()
                    page(forward = false)
                }

                event.wheelRotation > 0 && atBottom -> {
                    event.consume()
                    page(forward = true)
                }
            }
        }
    }

    /**
     * 全章节每页行数 = 0（按窗口高度自适应）: measure how many display lines fit
     * into the viewport and hand the value to the engine's [ReadingEngine.step].
     */
    private fun installAdaptivePageSize() {
        val viewport = scroll.viewport ?: return
        viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                updateAdaptiveLines()
                refresh()
            }
        })
    }

    private fun updateAdaptiveLines() {
        if (ReaderSettings.instance().linesPerPage != 0) {
            engine.adaptiveLinesPerPage = 0
            return
        }
        if (!this::scroll.isInitialized) return
        val viewport = scroll.viewport ?: return
        val height = viewport.height - textArea.insets.top - textArea.insets.bottom
        if (height <= 0) return // not laid out yet — keep the last known value
        val lineH = textArea.getFontMetrics(textArea.font).height.coerceAtLeast(1)
        engine.adaptiveLinesPerPage = (height / lineH).coerceIn(1, 200)
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

        /** How long a flashInfo message stays before the real info row returns. */
        const val INFO_FLASH_MS = 1800
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
