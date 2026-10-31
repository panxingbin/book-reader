package com.bookreader.reader.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 章节正则输入框（本地 txt 章节模式选书后弹出）。
 *
 * Previously a plain input dialog appeared on **every** open of a chapters-mode
 * book even though the regex was remembered. Now the controller only shows this
 * dialog for a book it has never read before, and the 「不再询问」 checkbox here
 * silences the prompt permanently (it can be turned back on in the settings by
 * editing the regex — or flipping 是否询问章节正则 there).
 */
class ChapterRegexDialog(
    project: Project,
    initialRegex: String,
) : DialogWrapper(project) {

    private val field = JBTextField(initialRegex)
    private val doNotAsk = JBCheckBox("记住此正则，以后打开书籍时不再询问（可在设置中恢复）")

    init {
        title = "章节正则"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(130))
        panel.add(
            JLabel(
                "<html>请输入【章节标题】正则（默认 ${com.bookreader.reader.ReaderSettings.DEFAULT_CHAPTER_REGEX}）：<br>" +
                    "示例：【第.*章.*】匹配【第1238章 标题】；加上 ^ 可只识别行首。</html>",
            ),
            BorderLayout.NORTH,
        )
        panel.add(field, BorderLayout.CENTER)
        panel.add(doNotAsk, BorderLayout.SOUTH)
        return panel
    }

    fun regex(): String = field.text.trim()

    fun doNotAskAgain(): Boolean = doNotAsk.isSelected
}
