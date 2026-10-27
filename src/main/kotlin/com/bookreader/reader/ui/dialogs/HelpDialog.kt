package com.bookreader.reader.ui.dialogs

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 使用说明 / 帮助 — the menu map, copied from the reference plugin's help text so
 * users migrating from it recognise every entry.
 */
class HelpDialog(private val project: Project) : DialogWrapper(project) {

    init {
        title = "使用说明 / 帮助"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JTextArea(HELP_TEXT).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
            caretPosition = 0
        }

        val buttons = JPanel()
        buttons.add(JButton("打开 Gitee 项目").apply {
            addActionListener { BrowserUtil.browse("https://gitee.com/wind_invade/chapter_reader/") }
        })
        buttons.add(JButton("打开在线阅读配置").apply {
            addActionListener { BrowserUtil.browse("https://gitee.com/wind_invade/chapter_reader/blob/master/chapter_config-v1.json") }
        })

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520))
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private companion object {
        val HELP_TEXT = """
            插件操作：【IDEA 顶部 菜单栏】→【Tools】→【Read】
            如果没有显示，重启 IDEA 再次查看。
            本地 txt 文件阅读，需要修改文件编码为【UTF8】。

            【在线阅读模式】
             1. 先使用【正常网站列表】，选择某一个网站，寻找想看的小说。
             2. 输入小说【章节目录】的 URL。
             3. 展示位置默认【左下角】，【插件设置】可以修改展示方式，支持“章节跳转”。

            【在线全章节阅读模式】
             该模式可在【Read】窗口全章阅读（隐蔽性不佳），
             除了展示方式与翻页方式，其它操作与【在线阅读模式】一致。

            【正常网站列表】
             gitee 上已配置且节点配置正常的网站，在此随意打开一个网站寻找想看的小说即可。

            【保存网站到本地】
             获取配置所需节点的 FullPath，将输入的 URL 的网站保存在选择的位置。

            【网站节点调试】
             将网站获取到的 FullPath 进行调试，调试成功后，
             一键复制配置提交到 gitee，审核通过后即可阅读。

            【本地阅读模式】
             1. 选择小说文件位置，根据小说【章节名】写一个简单的正则（默认 ^第.*章.*）。
             2. 展示位置默认【左下角】，支持“章节跳转”。

            【本地全章节阅读模式】
             该模式可在【Read】窗口全章阅读（隐蔽性不佳）。

            【Epub阅读模式 / Epub全章节阅读模式】
             文件仅支持 Epub 格式，展示位置默认【左下角】，支持“章节跳转”。

            【简易文章阅读模式】
             1. 仅按【插件设置】→【单行展示最大字数】切分内容。
             2. 展示位置默认【左下角】。

            【简易文本阅读模式】
             1. 仅按【文件换行符】分行，不受【单行展示最大字数】限制。
             2. 单行字数过多可能会展示不全。

            【快速清屏 / 有人来了就按我】
             一键隐藏全部阅读内容（清屏）。阅读位置会保留，再次按
             【有人来了就按我】(Ctrl+Alt+B) 即可恢复，无需重新加载。

            【重新初始化】
             遇到问题，可尝试重新读取。

            【章节列表 / 跳转】
             展示章节列表，并且可以跳转。跳转后按【下一页】即是最新位置。

            【内容跳转】
             内容行号的跳转。跳转后按【下一页】即是最新位置。

            【上一页 / 下一页】
             非【全章节】阅读模式，使用此操作。默认快捷键 Ctrl+Alt+2 / Ctrl+Alt+3。

            【自动下一页【再次按下则停止】】
             非【全章节】阅读模式，使用此操作。默认快捷键 Ctrl+Alt+4。

            【字体选择】【内容过滤】【历史记录】
             字体颜色及大小的配置也会自动保存；字体颜色输入框输入 16 进制颜色，输入有误会自动恢复默认颜色。

            【自定义快捷键】
             File | Settings | Keymap | Plugins | Book Reader，可自定义快捷键。

            【文档】
             https://gitee.com/wind_invade/chapter_reader/blob/master/README.md
             https://www.cnblogs.com/winddniw/p/15689784.html
        """.trimIndent()
    }
}
