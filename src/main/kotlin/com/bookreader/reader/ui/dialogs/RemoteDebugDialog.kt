package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReaderSettings
import com.bookreader.reader.remote.Http
import com.bookreader.reader.remote.RemoteChapterReader
import com.bookreader.reader.remote.SiteConfig
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 网站节点调试 — prove that a `chapterPath` / `contentPath` pair works before
 * contributing it to the shared configuration.
 *
 * This is the tool the reference plugin's "网站节点调试" menu item provides: paste
 * the FullPath nodes copied from the browser's dev tools, hit 解析, and either see
 * the parsed chapters or a clear failure. Once it parses, 一键复制配置并打开Gitee提交
 * puts the ready-made JSON entry on the clipboard and opens the shared file.
 */
class RemoteDebugDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = ReaderSettings.instance()

    private val urlField = JBTextField()
    private val chapterPathField = JBTextField()
    private val contentPathField = JBTextField()
    private val nextUrlPathField = JBTextField()
    private val nextPathTextField = JBTextField("下一页")
    private val nextLoopBox = JBCheckBox("循环加载下一页（章节内容分多页时勾选）")
    private val nextLoopCntField = JBTextField("15")

    private val output = JTextArea().apply {
        isEditable = false
        lineWrap = true
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        rows = 12
    }

    init {
        title = "网站节点调试"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun add(label: String, component: JComponent) {
            val c = GridBagConstraints()
            c.insets = Insets(2, 4, 2, 4)
            c.anchor = GridBagConstraints.WEST
            c.gridx = 0
            c.gridy = row
            form.add(JBLabel(label), c)
            c.gridx = 1
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 1.0
            form.add(component, c)
            row++
        }

        add("文章目录 / 章节 URL：", urlField)
        add("chapterPath 目录节点：", chapterPathField)
        add("contentPath 内容节点：", contentPathField)
        add("nextUrlPath 下一页节点：", nextUrlPathField)
        add("nextPathText 翻页文本：", nextPathTextField)
        add("nextLoopCnt 最大循环：", nextLoopCntField)

        val loopRow = GridBagConstraints()
        loopRow.insets = Insets(2, 4, 2, 4)
        loopRow.gridx = 0
        loopRow.gridy = row
        loopRow.gridwidth = 2
        loopRow.anchor = GridBagConstraints.WEST
        form.add(nextLoopBox, loopRow)
        row++

        val actions = JPanel()
        actions.add(JButton("解析").apply { addActionListener { runParse() } })
        actions.add(JButton("一键复制配置并打开 Gitee 提交").apply { addActionListener { copyAndOpen() } })
        actions.add(JButton("用此配置开始阅读").apply { addActionListener { readWithThis() } })

        val top = JPanel(BorderLayout())
        top.add(form, BorderLayout.CENTER)
        top.add(actions, BorderLayout.SOUTH)

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(520))
        panel.add(
            JBLabel("<html>推荐使用 class(.xxx) 或 id(#xxx)；不唯一时使用 FullPath。<br>" +
                "<i>部分网站的动态节点需要先“保存网页到本地”，再打开保存的文件获取 FullPath。</i></html>"),
            BorderLayout.NORTH,
        )
        panel.add(top, BorderLayout.CENTER)
        panel.add(JBScrollPane(output), BorderLayout.SOUTH)
        return panel
    }

    private fun currentConfig(): SiteConfig = SiteConfig(
        checkUrl = urlField.text.trim(),
        chapterPath = chapterPathField.text.trim(),
        contentPath = contentPathField.text.trim(),
        nextUrlPath = nextUrlPathField.text.trim(),
        nextPathText = nextPathTextField.text.trim(),
        nextLoop = nextLoopBox.isSelected,
        nextLoopCnt = nextLoopCntField.text.trim().toIntOrNull() ?: SiteConfig.DEFAULT_LOOP_COUNT,
    )

    private fun runParse() {
        val url = urlField.text.trim()
        if (!Http.isHttpUrl(url)) {
            append("【出错】URL 需要以 http:// 或 https:// 开头")
            return
        }
        val config = currentConfig()
        val timeoutMs = settings.remoteTimeoutSeconds.coerceIn(1, 120) * 1000

        output.text = ""
        append("开始解析：$url")

        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()

            val chapters = RemoteChapterReader.chapters(url, config, timeoutMs)
            val report = StringBuilder()
            if (chapters.isEmpty()) {
                report.append("根据 chapterPath：${config.chapterPath}，获取节点数为空。\n")
                report.append("请检查是否为【目录页面的 URL】或【尝试重新获取 FullPath 节点】。\n")
            } else {
                report.append("目录解析成功，共 ${chapters.size} 章，耗时 ${System.currentTimeMillis() - started}/ms\n")
                chapters.take(5).forEach { report.append("  ${it.number}. ${it.title}  →  ${it.url}\n") }
                if (chapters.size > 5) report.append("  …\n")
            }

            val firstUrl = chapters.firstOrNull()?.url ?: url
            val content = RemoteChapterReader.chapterText(firstUrl, config, timeoutMs)
            if (content.isNullOrBlank()) {
                report.append("根据 contentPath：${config.contentPath}，获取内容为空。\n")
                report.append("请尝试重新获取 FullPath 节点。\n")
            } else {
                report.append("内容解析成功，共 ${content.length} 字，预览：\n")
                report.append(content.take(300)).append("…\n")
            }

            val finalReport = report.toString()
            ApplicationManager.getApplication().invokeLater { append(finalReport) }
        }
    }

    private fun copyAndOpen() {
        val host = Http.hostOf(urlField.text.trim())
        if (host.isNullOrBlank()) {
            append("【出错】请先填写一个可解析的 URL")
            return
        }
        val json = "\"$host\": ${currentConfig().toJson().toJSONString()}"
        CopyPasteManager.getInstance().setContents(StringSelection(json))
        append("已复制配置到剪贴板，请在打开的页面中粘贴到 chapter_config-v1.json：\n$json")
        BrowserUtil.browse(GITEE_EDIT_URL)
    }

    private fun readWithThis() {
        val url = urlField.text.trim()
        val host = Http.hostOf(url) ?: return
        com.bookreader.reader.remote.SiteConfigRepository.getInstance().putLocal(host, currentConfig())
        append("已把该配置加入本次会话的站点表，可以直接开始阅读。")
        close(OK_EXIT_CODE)
        com.bookreader.reader.ReaderController.getInstance(project).load(
            com.bookreader.reader.ReaderMode.REMOTE, url,
        )
    }

    private fun append(text: String) {
        output.append(text)
        if (!text.endsWith("\n")) output.append("\n")
    }

    private companion object {
        const val GITEE_EDIT_URL =
            "https://gitee.com/wind_invade/chapter_reader/edit/master/chapter_config-v1.json"
    }
}
