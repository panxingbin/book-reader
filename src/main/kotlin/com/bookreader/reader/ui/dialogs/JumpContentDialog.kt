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
import javax.swing.JSlider

/**
 * 内容跳转 — jump to a display line by number, or drag a progress slider.
 *
 * The current position is pre-filled so the dialog doubles as "where am I".
 * The slider is the practical way to aim inside a book: a display-line number
 * (post-wrapping) is hard to estimate, a percentage is not.
 */
class JumpContentDialog(private val project: Project) : DialogWrapper(project) {

    private val engine: ReadingEngine get() = project.getService(ReadingEngine::class.java)
    private val field = JBTextField((engine.lineIndex + 1).toString())
    private val slider = JSlider(0, 100)
    private val sliderLabel = JLabel()

    private val total: Int = engine.book?.lines?.size ?: 0

    init {
        title = "内容跳转"
        init()

        slider.isEnabled = total > 1
        slider.value = currentPercent()
        slider.toolTipText = "按百分比拖动，行号会跟着填进输入框"
        sliderLabel.text = describe(slider.value)
        slider.addChangeListener {
            field.text = lineOf(slider.value).toString()
            sliderLabel.text = describe(slider.value)
        }
    }

    override fun createCenterPanel(): JComponent {
        field.preferredSize = Dimension(JBUI.scale(120), field.preferredSize.height)

        val bottom = JPanel(BorderLayout(0, 2))
        bottom.add(slider, BorderLayout.CENTER)
        bottom.add(sliderLabel, BorderLayout.SOUTH)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JLabel("跳转到第几行（1 - $total），或拖动滑条按百分比："), BorderLayout.NORTH)
        panel.add(field, BorderLayout.CENTER)
        panel.add(bottom, BorderLayout.SOUTH)
        return panel
    }

    private fun currentPercent(): Int {
        if (total <= 1) return 0
        return engine.lineIndex * 100 / (total - 1)
    }

    private fun lineOf(percent: Int): Int {
        if (total <= 0) return 1
        return (percent * (total - 1) / 100) + 1
    }

    private fun describe(percent: Int): String = "约 $percent%（第 ${lineOf(percent)} 行）"

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
