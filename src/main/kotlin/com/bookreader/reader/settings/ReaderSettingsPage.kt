package com.bookreader.reader.settings

import com.bookreader.reader.BookLibrary
import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderSettings
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * 插件设置 — Settings → Tools → Book Reader.
 *
 * Mirrors the reference plugin's settings dialog item for item (its
 * `chapter_message_zh.properties` items 148–159), including the cached online
 * configuration, which can be pasted in by hand when Gitee is unreachable.
 *
 * Values are written to [ReaderSettings] as the user edits, so nothing depends on
 * remembering to press Apply.
 */
class ReaderSettingsPage : Configurable {

    private var changed = false

    private lateinit var enabledBox: JCheckBox
    private lateinit var timeoutField: JTextField
    private lateinit var maxCharsField: JTextField
    private lateinit var styleCombo: JComboBox<ReaderSettings.DisplayStyle>
    private lateinit var styleHint: JBLabel
    private lateinit var navBarField: JTextField
    private lateinit var tailLineBox: JCheckBox
    private lateinit var debugBox: JCheckBox
    private lateinit var loadLogBox: JCheckBox
    private lateinit var clearTextField: JTextField
    private lateinit var regexField: JTextField
    private lateinit var filterArea: JTextArea
    private lateinit var fontFamilyField: JTextField
    private lateinit var fontSizeField: JTextField
    private lateinit var fontColorField: JTextField
    private lateinit var linesPerPageField: JTextField
    private lateinit var autoNextField: JTextField
    private lateinit var configUrlField: JTextField
    private lateinit var cacheStatusLabel: JBLabel
    private lateinit var bookDirectoryField: JTextField

    override fun getDisplayName(): String = "Book Reader"

    override fun createComponent(): JComponent {
        val s = ReaderSettings.instance()

        enabledBox = JCheckBox("是否开启插件", s.enabled)
        timeoutField = JTextField(s.remoteTimeoutSeconds.toString(), 6)
        maxCharsField = JTextField(s.maxCharsPerLine.toString(), 6)
        styleCombo = JComboBox(ReaderSettings.DisplayStyle.entries.toTypedArray()).apply {
            selectedItem = s.displayStyle
            // Without a renderer the combo shows the raw enum constant
            // (BOTTOM_LEFT / CODE_POPUP / NAV_BAR) — the user's complaint was
            // exactly that the setting looked like source code. Show the
            // plain-Chinese title instead.
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ): java.awt.Component {
                    val comp = super.getListCellRendererComponent(list, value, index, selected, hasFocus)
                    if (comp is javax.swing.JLabel) {
                        comp.text = (value as? ReaderSettings.DisplayStyle)?.title
                            ?: value?.toString() ?: ""
                    }
                    return comp
                }
            }
        }
        styleHint = JBLabel(styleDescription(s.displayStyle)).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(4)
        }
        navBarField = JTextField(s.navBarMaxChars.toString(), 6)
        tailLineBox = JCheckBox("是否开启尾部行号", s.showTailLineNumber)
        debugBox = JCheckBox("是否开启调试日志打印", s.debugLog)
        loadLogBox = JCheckBox("是否开启加载日志打印", s.loadLog)
        clearTextField = JTextField(s.clearText, 20)
        regexField = JTextField(s.chapterRegex, 24)
        filterArea = JTextArea(s.contentFilter, 4, 30).apply { lineWrap = false }
        fontFamilyField = JTextField(s.fontFamily, 16)
        fontSizeField = JTextField(s.fontSize.toString(), 6)
        fontColorField = JTextField(s.fontColorHex, 10)
        linesPerPageField = JTextField(s.linesPerPage.toString(), 6)
        autoNextField = JTextField(s.autoNextSeconds.toString(), 6)
        configUrlField = JTextField(s.remoteConfigUrl, 40)
        cacheStatusLabel = JBLabel(cacheDescription())
        bookDirectoryField = JTextField(BookLibrary.instance().bookDirectory, 34).apply {
            isEditable = false
            toolTipText = "所有 IDEA 窗口共用这个目录；本地阅读选书时从这里开始"
        }

        installListeners(s)

        val form = JPanel(GridBagLayout())
        var row = 0
        fun section(text: String) {
            val c = GridBagConstraints()
            c.gridx = 0
            c.gridy = row++
            c.gridwidth = 2
            c.anchor = GridBagConstraints.WEST
            c.insets = Insets(10, 4, 2, 4)
            form.add(JLabel(text).apply { font = font.deriveFont(Font.BOLD) }, c)
        }

        fun field(label: String, component: JComponent) {
            val l = GridBagConstraints()
            l.gridx = 0
            l.gridy = row
            l.anchor = GridBagConstraints.WEST
            l.insets = Insets(2, 4, 2, 4)
            form.add(JLabel(label), l)

            val v = GridBagConstraints()
            v.gridx = 1
            v.gridy = row++
            v.anchor = GridBagConstraints.WEST
            v.fill = GridBagConstraints.HORIZONTAL
            v.weightx = 1.0
            v.insets = Insets(2, 4, 2, 4)
            form.add(component, v)
        }

        fun stacked(label: String, vararg components: JComponent) {
            // A real vertical stack. The old version added every component to
            // BorderLayout.CENTER of the same panel, so the second add silently
            // replaced the first in the layout: the 正文显示样式 combo ended up
            // laid out at 0x0 (invisible, unclickable) with only the hint label
            // showing — "正文显示样式不能选择".
            val container = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            components.forEach {
                it.alignmentX = Component.LEFT_ALIGNMENT
                container.add(it)
            }
            field(label, container)
        }

        section("基础")
        field("是否开启插件：", enabledBox)
        stacked("正文显示样式：", styleCombo, styleHint)
        field("设置单行展示最大字数：", maxCharsField)
        field("导航栏单行最大字数(建议<=45，选导航栏样式时生效)：", navBarField)
        field("是否开启尾部行号：", tailLineBox)
        field("设置清屏文本：", clearTextField)
        field("远程读取超时时间(x/秒)：", timeoutField)

        section("书籍目录")
        field("所有窗口共享的书籍目录：", buildBookDirectoryPanel())

        section("阅读")
        field("全章节每页行数：", linesPerPageField)
        field("自动下一页间隔(x/秒)：", autoNextField)
        field("章节正则（本地 txt / epub）：", regexField)
        field("Read窗口字体：", fontFamilyField)
        field("字号：", fontSizeField)
        field("字体颜色(16进制，如 333333，可留空跟随主题)：", fontColorField)

        section("过滤与日志")
        field("内容过滤（每行一个正则）：", JBScrollPane(filterArea).apply {
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(90))
        })
        field("是否开启调试日志打印：", debugBox)
        field("是否开启加载日志打印：", loadLogBox)

        section("在线阅读配置")
        field("设置在线阅读配置地址：", configUrlField)
        field("本地缓存（离线时也能阅读）：", buildCachePanel())

        val scroll = JBScrollPane(form)
        scroll.preferredSize = Dimension(JBUI.scale(620), JBUI.scale(560))
        return scroll
    }

    /**
     * 书籍目录 — one folder for every window.
     *
     * The book picker remembers the directory automatically; this row exists so
     * it can also be set (or cleared) from one place without opening a book
     * first, and so it is obvious that the setting is not per project.
     */
    private fun buildBookDirectoryPanel(): JComponent {
        val row = JPanel(BorderLayout(6, 0))
        row.add(bookDirectoryField, BorderLayout.CENTER)

        val buttons = JPanel()
        buttons.add(JButton("选择目录…").apply { addActionListener { chooseBookDirectory() } })
        buttons.add(JButton("清空").apply {
            addActionListener {
                BookLibrary.instance().rememberDirectory("")
                bookDirectoryField.text = ""
            }
        })
        row.add(buttons, BorderLayout.EAST)

        // The directory is only the starting point — the book list is shown by
        // 打开书籍, so say so right here instead of leaving the row to look like
        // "this is where you pick a book".
        val hint = JBLabel(
            "选书入口：Tools → Read → 打开书籍…（Ctrl+Alt+O），或任一本地阅读模式；" +
                "对话框会列出此目录下的书籍，双击即读。",
        ).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(2)
        }

        val panel = JPanel(BorderLayout(0, 2))
        panel.add(row, BorderLayout.NORTH)
        panel.add(hint, BorderLayout.CENTER)
        return panel
    }

    private fun chooseBookDirectory() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("选择书籍目录")
        val current = BookLibrary.instance().bookDirectory
            .takeIf { it.isNotBlank() }
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, null, current) ?: return
        val directory = File(chosen.path)
        if (!directory.isDirectory) return

        BookLibrary.instance().rememberDirectory(directory.absolutePath)
        bookDirectoryField.text = directory.absolutePath
    }

    /**
     * One plain-Chinese line under the 正文显示样式 combo explaining what the
     * selected style actually shows, so users are not left guessing. The three
     * styles are mutually exclusive — the line is only ever rendered on ONE
     * surface, never on two at once.
     */
    private fun styleDescription(style: ReaderSettings.DisplayStyle?): String = when (style) {
        ReaderSettings.DisplayStyle.BOTTOM_LEFT ->
            "正文只显示在右下角状态栏，像普通状态栏文字（最隐蔽，推荐）"
        ReaderSettings.DisplayStyle.CODE_POPUP ->
            "正文只显示在编辑器里的灰色提示条（像代码提示），点状态栏可弹出完整阅读框"
        ReaderSettings.DisplayStyle.NAV_BAR ->
            "正文只显示在顶部导航栏（路径栏）里，建议把字数控制在 45 以内"
        else ->
            "正文只显示在右下角状态栏（最隐蔽，推荐）"
    }

    /**
     * The online configuration is cached in a file under the IDE system directory,
     * so this row shows its state and offers import / clear instead of a text area
     * that would hold 798 KB of JSON.
     */
    private fun buildCachePanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 0))
        panel.add(cacheStatusLabel, BorderLayout.CENTER)

        val buttons = JPanel()
        buttons.add(JButton("从剪贴板导入").apply { addActionListener { importFromClipboard() } })
        buttons.add(JButton("清空缓存").apply {
            addActionListener {
                com.bookreader.reader.remote.SiteConfigCache.clear()
                refreshCacheStatus()
            }
        })
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private fun cacheDescription(): String {
        val size = com.bookreader.reader.remote.SiteConfigCache.sizeBytes()
        val path = com.bookreader.reader.remote.SiteConfigCache.file().absolutePath
        return if (size <= 0) {
            "尚无本地缓存 —— 第一次在线阅读时会自动下载（或从剪贴板导入）"
        } else {
            "已缓存 ${size / 1024} KB → $path"
        }
    }

    private fun refreshCacheStatus() {
        if (::cacheStatusLabel.isInitialized) cacheStatusLabel.text = cacheDescription()
    }

    /** Import the shared configuration from the clipboard, for use while offline. */
    private fun importFromClipboard() {
        val text = runCatching { CopyPasteManager.getInstance().contents as? String }.getOrNull()
        val trimmed = text?.trim().orEmpty()

        if (!trimmed.startsWith("{")) {
            Messages.showWarningDialog(
                "剪贴板内容不是 JSON（应以 { 开头）。请复制 gitee 上 chapter_config-v1.json 的全部内容。",
                "导入在线配置",
            )
            return
        }

        com.bookreader.reader.remote.SiteConfigCache.write(trimmed)
        ReaderSettings.instance().remoteConfigJson = ""
        refreshCacheStatus()
        Messages.showInfoMessage(
            "已导入在线配置（${trimmed.length / 1024} KB）。重新阅读时会使用这份缓存。",
            "导入在线配置",
        )
    }

    private fun installListeners(s: ReaderSettings) {
        enabledBox.addItemListener { s.enabled = enabledBox.isSelected; changed = true; refresh() }
        tailLineBox.addItemListener { s.showTailLineNumber = tailLineBox.isSelected; changed = true; refresh() }
        debugBox.addItemListener { s.debugLog = debugBox.isSelected; changed = true }
        loadLogBox.addItemListener { s.loadLog = loadLogBox.isSelected; changed = true }
        styleCombo.addItemListener {
            (styleCombo.selectedItem as? ReaderSettings.DisplayStyle)?.let { style ->
                s.displayStyle = style
                styleHint.text = styleDescription(style)
            }
            changed = true
            refresh()
        }

        fun text(field: JTextField, apply: (String) -> Unit) {
            field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) { apply(field.text); changed = true }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) { apply(field.text); changed = true }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) { apply(field.text); changed = true }
            })
        }

        text(timeoutField) { s.remoteTimeoutSeconds = it.toIntOrNull()?.coerceIn(1, 120) ?: 10 }
        text(maxCharsField) { s.maxCharsPerLine = it.toIntOrNull()?.coerceIn(5, 500) ?: 30; refresh() }
        text(navBarField) { s.navBarMaxChars = it.toIntOrNull()?.coerceIn(10, 45) ?: 45; refresh() }
        text(clearTextField) { s.clearText = it }
        text(regexField) { s.chapterRegex = it }
        text(fontFamilyField) { s.fontFamily = it; refresh() }
        text(fontSizeField) { s.fontSize = it.toIntOrNull()?.coerceIn(8, 72) ?: 14; refresh() }
        text(fontColorField) { s.fontColorHex = it; refresh() }
        text(linesPerPageField) { s.linesPerPage = it.toIntOrNull()?.coerceIn(1, 200) ?: 30; refresh() }
        text(autoNextField) { s.autoNextSeconds = it.toIntOrNull()?.coerceIn(1, 600) ?: 5 }
        text(configUrlField) { s.remoteConfigUrl = it }

        filterArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { s.contentFilter = filterArea.text; changed = true }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { s.contentFilter = filterArea.text; changed = true }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { s.contentFilter = filterArea.text; changed = true }
        })
    }

    /** Re-render the status bar of every open project after a display change. */
    private fun refresh() {
        for (project in ProjectManager.getInstance().openProjects) {
            ReaderController.getInstance(project).refresh()
        }
    }

    override fun isModified(): Boolean = changed

    override fun apply() {
        changed = false
        refresh()
    }

    override fun reset() {
        val s = ReaderSettings.instance()
        enabledBox.isSelected = s.enabled
        timeoutField.text = s.remoteTimeoutSeconds.toString()
        maxCharsField.text = s.maxCharsPerLine.toString()
        styleCombo.selectedItem = s.displayStyle
        styleHint.text = styleDescription(s.displayStyle)
        navBarField.text = s.navBarMaxChars.toString()
        tailLineBox.isSelected = s.showTailLineNumber
        debugBox.isSelected = s.debugLog
        loadLogBox.isSelected = s.loadLog
        clearTextField.text = s.clearText
        regexField.text = s.chapterRegex
        filterArea.text = s.contentFilter
        fontFamilyField.text = s.fontFamily
        fontSizeField.text = s.fontSize.toString()
        fontColorField.text = s.fontColorHex
        linesPerPageField.text = s.linesPerPage.toString()
        autoNextField.text = s.autoNextSeconds.toString()
        configUrlField.text = s.remoteConfigUrl
        bookDirectoryField.text = BookLibrary.instance().bookDirectory
        refreshCacheStatus()
        changed = false
    }

    override fun disposeUIResources() = Unit
}
