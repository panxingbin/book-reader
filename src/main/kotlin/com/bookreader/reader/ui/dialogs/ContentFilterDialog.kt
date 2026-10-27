package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReaderSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 内容过滤 — one regular expression per line; matching display lines are dropped.
 *
 * This is the escape hatch for the ad lines novel sites splice into the middle of
 * a chapter ("本站域名 xxx.com，请记住…").
 */
class ContentFilterDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = ReaderSettings.instance()

    private val area = JTextArea(settings.contentFilter, 12, 40).apply {
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
    }

    init {
        title = "内容过滤"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(320))
        panel.add(
            JBLabel("<html>每行一个正则，匹配到的行会被丢弃。<br>例如：<code>本站|请记住|最新章节</code></html>"),
            BorderLayout.NORTH,
        )
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.add(
            JBLabel("<html><i>保存后请使用【重新初始化】重新解析，过滤才会生效。</i></html>"),
            BorderLayout.SOUTH,
        )
        return panel
    }

    override fun doOKAction() {
        settings.contentFilter = area.text
        super.doOKAction()
    }
}
