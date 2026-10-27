package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 字体选择 — family, size and colour of the **Read** window text.
 *
 * The colour is typed as `RRGGBB` (no `#`), matching the reference plugin's
 * "输入想要的【16进制】字体颜色"; an unparsable value simply falls back to the
 * theme colour, which the settings page warns about.
 */
class FontDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = ReaderSettings.instance()

    private val families = JComboBox(
        arrayOf("") + GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted(),
    ).apply {
        selectedItem = settings.fontFamily
        isEditable = true
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
    }

    private val sizeSpinner = JSpinner(SpinnerNumberModel(settings.fontSize.coerceIn(8, 72), 8, 72, 1))

    private val colorField = JBTextField(settings.fontColorHex).apply {
        preferredSize = Dimension(JBUI.scale(100), preferredSize.height)
    }

    private val preview = JBLabel("预览：床前明月光，疑是地上霜。").apply {
        border = JBUI.Borders.empty(8)
    }

    init {
        title = "字体选择"
        init()
        updatePreview()
        sizeSpinner.addChangeListener { updatePreview() }
        colorField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePreview()
        })
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(java.awt.GridLayout(3, 2, 8, 8))
        form.add(JBLabel("字体："))
        form.add(families)
        form.add(JBLabel("字号："))
        form.add(sizeSpinner)
        form.add(JBLabel("颜色（16 进制，如 3C8CFF）："))
        form.add(colorField)

        val panel = JPanel(BorderLayout(0, 8))
        panel.add(form, BorderLayout.NORTH)
        panel.add(preview, BorderLayout.CENTER)
        panel.add(
            JBLabel("<html><i>留空表示跟随 IDE 主题。输入有误会自动恢复为默认颜色。</i></html>"),
            BorderLayout.SOUTH,
        )
        return panel
    }

    private fun updatePreview() {
        val family = (families.selectedItem as? String).orEmpty().ifBlank { Font.MONOSPACED }
        preview.font = Font(family, Font.PLAIN, (sizeSpinner.value as Int).coerceIn(8, 72))
        preview.foreground = parseColor() ?: javax.swing.UIManager.getColor("Label.foreground")
    }

    private fun parseColor(): java.awt.Color? {
        val hex = colorField.text.trim().removePrefix("#")
        if (hex.length != 6) return null
        return runCatching { java.awt.Color(hex.toInt(16)) }.getOrNull()
    }

    override fun doOKAction() {
        settings.fontFamily = (families.selectedItem as? String).orEmpty().trim()
        settings.fontSize = (sizeSpinner.value as Int).coerceIn(8, 72)
        settings.fontColorHex = colorField.text.trim().removePrefix("#")
        super.doOKAction()
        ReaderController.getInstance(project).refresh()
    }
}
