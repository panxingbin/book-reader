package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReadingEngine
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 内容跳转 — jump to a display line by number.
 *
 * The current position is pre-filled so the dialog doubles as "where am I".
 */
class JumpContentDialog(private val project: Project) : DialogWrapper(project) {

    private val engine: ReadingEngine get() = project.getService(ReadingEngine::class.java)
    private val field = JBTextField((engine.lineIndex + 1).toString())

    init {
        title = "内容跳转"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val book = engine.book
        val total = book?.lines?.size ?: 0
        field.preferredSize = Dimension(JBUI.scale(120), field.preferredSize.height)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JLabel("跳转到第几行（1 - $total）："), BorderLayout.NORTH)
        panel.add(field, BorderLayout.CENTER)
        panel.add(JLabel("提示：跳转后按【上一页/下一页】即从新位置继续。"), BorderLayout.SOUTH)
        return panel
    }

    override fun doOKAction() {
        val line = field.text.trim().toIntOrNull()
        if (line == null || line < 1) {
            setErrorText("请输入一个大于 0 的行号")
            return
        }
        engine.goToLine(line - 1)
        super.doOKAction()
    }
}
