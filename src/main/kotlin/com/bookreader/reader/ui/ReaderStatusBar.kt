package com.bookreader.reader.ui

import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderLog
import com.bookreader.reader.ReaderSettings
import com.bookreader.reader.ReadingEngine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * 普通阅读模式 — the discreet surface.
 *
 * With 正文显示样式 = 左下角（状态栏）, the current line lives in the IDE status bar,
 * truncated to [ReaderSettings.statusBarWidth]. It looks like an ordinary status-bar
 * indicator, which is the whole point: nobody walking past sees a novel.
 *
 * With the other two styles the line lands in an editor hint or the navigation
 * bar instead; the status bar then only shows a short "📖 已加载" indicator.
 *
 * Clicking the status-bar item opens [ReaderPopupPanel], a monospaced popup that
 * reads like an editor tooltip.
 */
object ReaderStatusBar {

    /**
     * The widget installed in [project]'s status bar, if any.
     *
     * Registration goes through the `statusBarWidgetFactory` extension point
     * (`BookReaderStatus` in plugin.xml), which is the only supported way to put a
     * widget in the bar. `StatusBar.addWidget(StatusBarWidget)` is deprecated upstream
     * ("Use StatusBarWidgetFactory") and is reported by the Marketplace verifier, so
     * there is deliberately no manual fallback here.
     */
    fun widgetOf(project: Project): ReaderStatusBarWidget? {
        val bar = WindowManager.getInstance().getStatusBar(project) ?: return null
        return bar.getWidget(ReaderStatusBarWidget.WIDGET_ID) as? ReaderStatusBarWidget
    }

    /** Push the current line onto the configured reading surface. */
    fun update(project: Project) {
        // Everything below touches Swing state, so it all runs on the EDT.
        ApplicationManager.getApplication().invokeLater {
            val settings = ReaderSettings.instance()
            val controller = ReaderController.getInstance(project)
            val engine = project.getService(ReadingEngine::class.java)

            val widget = widgetOf(project)

            if (!settings.enabled || controller.hidden) {
                widget?.showIdle(settings.clearText.ifBlank { "—" })
                ReaderSurface.hide(project)
                return@invokeLater
            }

            val book = engine.book
            val line = engine.currentLine()
            if (book == null || line == null) {
                if (settings.displayStyle == ReaderSettings.DisplayStyle.BOTTOM_LEFT) {
                    widget?.showIdle("—")
                } else {
                    // 正文不归状态栏管的样式，连「—」也不显示。
                    widget?.showBlank()
                }
                return@invokeLater
            }

            // 只有「状态栏（右下角文字）」样式把正文放进状态栏；其余样式正文只出现在
            // 编辑器灰条或导航栏，状态栏**完全空白**——右下角一行「📖 书名 · 已加载」
            // 同样会暴露阅读状态，等于把导航栏样式的隐蔽性抵消掉
            // （用户反馈：设置了顶部导航栏，右下角还是显示书名+已加载）。
            when (settings.displayStyle) {
                ReaderSettings.DisplayStyle.BOTTOM_LEFT ->
                    widget?.showLine(
                        text = line.take(settings.statusBarWidth()),
                        tooltip = buildTooltip(project, engine),
                    )

                else -> widget?.showBlank()
            }

            // Hint（编辑器灰条）与导航栏由 ReaderSurface 按样式分发；状态栏样式时
            // 这里是 no-op，避免同一行正文同时挂在两个界面上。
            ReaderSurface.show(project, ReaderSurface.activeEditor(project), line)
        }
    }

    private fun buildTooltip(project: Project, engine: ReadingEngine): String {
        val book = engine.book ?: return "Book Reader"
        val chapter = engine.currentChapter()
        val chapterText = if (chapter >= 0 && chapter < book.chapters.size) {
            "第 ${chapter + 1} 章 ${book.chapters[chapter].title}"
        } else {
            book.title
        }
        return "$chapterText · ${engine.lineIndex + 1}/${book.lines.size} 行 · ${engine.progressPercent()}%" +
            if (engine.isAutoNext) " · 自动翻页中" else ""
    }
}

/**
 * The status bar widget itself.
 *
 * `TextPresentation` + `getClickConsumer()` is the supported way to make a
 * status bar item clickable; the platform owns the Swing component and asks for
 * the text on every repaint, so the current line simply lives in the widget's
 * fields.
 */
class ReaderStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    @Volatile
    private var text: String = "—"

    @Volatile
    private var tooltip: String = "Book Reader"

    @Volatile
    private var idle: Boolean = false

    private var popup: JBPopup? = null

    /**
     * When the popup last cancelled itself.
     *
     * A focusable popup is cancelled on the **mouse press** of a click outside it
     * (the status-bar widget included) and the click is still delivered to
     * [getClickConsumer] afterwards, so without this timestamp the second click
     * would reopen the popup instead of closing it.
     */
    @Volatile
    private var popupCancelledAt: Long = 0L

    /**
     * The status bar we were installed into, kept from [install].
     *
     * Writing [text] alone changes nothing on screen: the platform only re-reads
     * `getText()` when it is told to, through [StatusBar.updateWidget]. Without this
     * the widget sat there showing the initial "—" forever, which is exactly the
     * "书能加载但状态栏不显示" symptom.
     */
    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getAlignment(): Float = java.awt.Component.LEFT_ALIGNMENT

    override fun getTooltipText(): String = tooltip

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        popup?.cancel()
        popup = null
        statusBar = null
    }

    /** Ask the platform to re-read [getText] / [getTooltipText]. */
    private fun repaint() {
        // The widget can be updated from a background thread; updateWidget must run
        // on the EDT.
        ApplicationManager.getApplication().invokeLater {
            runCatching { statusBar?.updateWidget(WIDGET_ID) }
        }
    }

    /**
     * Click handling, delegated to the platform through the presentation.
     * The left button opens or closes the reading popup.
     */
    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? =
        com.intellij.util.Consumer { event: MouseEvent ->
            if (event.button == MouseEvent.BUTTON1) togglePopup(RelativePoint(event))
        }

    /** Show a reading line, or the idle placeholder. */
    fun showLine(text: String, tooltip: String) {
        this.text = text.ifBlank { " " }
        this.tooltip = tooltip
        this.idle = false
        repaint()
    }

    /** The status-bar indicator shown when the line lives elsewhere (hint / navbar). */
    fun showIdle(text: String) {
        this.text = text.ifBlank { "—" }
        this.tooltip = "Book Reader"
        this.idle = true
        repaint()
    }

    /**
     * Show nothing at all.
     *
     * Used by the 导航栏 / 代码提示 styles: the status bar must carry no trace of
     * the reader (not even a "📖 书名 · 已加载" line or a lone "—"), otherwise the
     * discreetness those styles exist for is cancelled out by the bottom-right
     * corner. A single space keeps the widget's slot reserved without painting
     * any glyph.
     */
    fun showBlank() {
        this.text = " "
        this.tooltip = "Book Reader"
        this.idle = true
        repaint()
    }

    val isIdle: Boolean get() = idle

    private fun togglePopup(point: RelativePoint) {
        if (popup?.isVisible == true) {
            popup?.cancel()
            popup = null
            return
        }
        // The click that got here already dismissed the popup: it is a close,
        // not a request to open it again.
        if (System.currentTimeMillis() - popupCancelledAt < CLICK_GRACE_MS) return
        showPopup(point)
    }

    private fun showPopup(point: RelativePoint) {
        val engine = project.getService(ReadingEngine::class.java)
        val panel = ReaderPopupPanel(project)

        val builder = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle(engine.book?.title ?: "Book Reader")
            .setResizable(true)
            .setMovable(true)
            // Only opened on an explicit status-bar click, so taking focus is fine.
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelCallback {
                popupCancelledAt = System.currentTimeMillis()
                popup = null
                true
            }

        val created = builder.createPopup()
        popup = created
        runCatching { created.show(point) }
    }

    companion object {
        const val WIDGET_ID = "BookReaderStatus"

        /** Grace window for "this click closed the popup" — see [popupCancelledAt]. */
        private const val CLICK_GRACE_MS = 300L
    }
}

/** Registers [ReaderStatusBarWidget]; disabled while 是否开启插件 is off. */
class ReaderStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ReaderStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Book Reader"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = ReaderStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    override fun isConfigurable(): Boolean = true
}

/**
 * The popup shown by 代码弹出框 mode (and by clicking the status bar line).
 *
 * Deliberately plain: monospaced text on a neutral background, so it reads like
 * an IDE tooltip rather than a book.
 */
class ReaderPopupPanel(private val project: Project) : JComponent() {

    private val textLabel = JLabel()
    private val infoLabel = JLabel()

    init {
        layout = BorderLayout()
        textLabel.verticalAlignment = SwingConstants.TOP
        textLabel.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        textLabel.border = JBUI.Borders.empty(8, 10)

        infoLabel.border = JBUI.Borders.empty(4, 10)
        infoLabel.font = infoLabel.font.deriveFont(Font.PLAIN, infoLabel.font.size2D - 1f)
        infoLabel.foreground = Color.GRAY
        infoLabel.horizontalAlignment = SwingConstants.LEFT

        add(textLabel, BorderLayout.CENTER)
        add(infoLabel, BorderLayout.SOUTH)

        preferredSize = java.awt.Dimension(JBUI.scale(460), JBUI.scale(200))
        refresh()
    }

    fun refresh() {
        val engine = project.getService(ReadingEngine::class.java)
        val book = engine.book
        if (book == null) {
            textLabel.text = "<html>还没有开始阅读。<br>菜单：Tools → Read</html>"
            infoLabel.text = ""
            return
        }

        val lines = engine.pageLines()
        val html = StringBuilder("<html><body style='width:420px;'>")
        for (line in lines) {
            html.append(escape(line)).append("<br>")
        }
        html.append("</body></html>")
        textLabel.text = html.toString()

        val chapter = engine.currentChapter()
        val chapterText = if (chapter in book.chapters.indices) "第 ${chapter + 1} 章  " else ""
        infoLabel.text = "  $chapterText${engine.lineIndex + 1}/${book.lines.size} 行 · ${engine.progressPercent()}%" +
            "   [Ctrl+Alt+2 上一页 · Ctrl+Alt+3 下一页 · Ctrl+Alt+B 清屏]"
    }

    private fun escape(line: String): String =
        line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").ifBlank { "&nbsp;" }
}
