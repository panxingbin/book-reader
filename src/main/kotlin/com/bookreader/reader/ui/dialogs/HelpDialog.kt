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

            【Read 窗口】
             右侧边栏的 Read 图标点一次打开、再点一次收起；
             也可以用 Ctrl+Alt+W（Read窗口切换至本项目）切换。

            【功能菜单】
             Read 窗口标题行的小齿轮打开一个可搜索的功能清单（分组、图标、快捷键、说明），
             双击即执行，Esc / 取消关闭；与 Tools → Read 菜单是同一套动作。

            【打开书籍…】
             一个入口选书（快捷键 Ctrl+Alt+O）：对话框有两个页签 ——
             【目录书籍】按目录列出全部书籍（txt / epub），下方选【阅读方式】，双击即读；
             【历史记录】直接挑以前读过的书，会从上次读到的章节/行号继续。
             设置页的「书籍目录」只是记下书库位置，列书/选书都在这里。

            【本地阅读模式】
             1. 先选小说目录，目录下所有可读文件会自动列出，双击选择要读的书；
                根据小说【章节名】写一个简单的正则（默认 ^第.*章.*）。
                只有没读过的书才会询问正则；读过的书按记住的正则直接打开。
             2. 展示位置默认【左下角】，支持“章节跳转”。
             3. 选过的目录会被记住，所有 IDEA 窗口共用（新窗口无需重新选择）。

            【本地全章节阅读模式】
             该模式可在【Read】窗口全章阅读（隐蔽性不佳），选书方式与【本地阅读模式】相同。

            【Epub阅读模式 / Epub全章节阅读模式】
             文件仅支持 Epub 格式，同样先选目录再选文件，展示位置默认【左下角】，支持“章节跳转”。

            【简易文章阅读模式】
             1. 仅按【插件设置】→【单行展示最大字数】切分内容。
             2. 展示位置默认【左下角】。

            【简易文本阅读模式】
             1. 仅按【文件换行符】分行，不受【单行展示最大字数】限制。
             2. 单行字数过多可能会展示不全。

            【快速清屏 / 有人来了就按我】
             一键隐藏全部阅读内容（清屏）。阅读位置会保留，再次按
             【有人来了就按我】(Ctrl+Alt+X) 即可恢复，无需重新加载。

            【重新初始化】
             遇到问题，可尝试重新读取。

            【章节列表 / 跳转】
             展示章节列表，并且可以跳转。正在读的章节带 ▶ 标记。
             跳转后按【下一页】即是最新位置。

            【上一章 / 下一章】
             在章节之间直接跳转，默认快捷键 Ctrl+Alt+[ / Ctrl+Alt+]；
             Read 窗口工具栏也有同名按钮。

            【内容跳转】
             按行号或百分比（滑条）跳转。跳转后按【下一页】即是最新位置。

            【上一页 / 下一页】
             非【全章节】阅读模式，使用此操作。默认快捷键 Ctrl+Alt+2 / Ctrl+Alt+3。
             Read 窗口里也可直接用 → / Space / PgDn（下一页）、← / Shift+Space /
             PgUp（上一页）翻页；滚轮滚到页尾继续下滚即翻页。

            【自动下一页【再次按下则停止】】
             非【全章节】阅读模式，使用此操作。默认快捷键 Ctrl+Alt+4。
             间隔可在 Read 窗口（速度 − / 速度 +）或状态栏弹出框里即时调节。

            【字体选择】【内容过滤】【历史记录】
             字体颜色及大小的配置也会自动保存；字体颜色输入框输入 16 进制颜色，输入有误会自动恢复默认颜色。
             历史记录、书籍目录、上次读的书在所有 IDEA 窗口中共享：新开窗口会自动恢复上次的书，
             各窗口的阅读进度仍各自独立。

            【自定义快捷键】
             File | Settings | Keymap | Plugins | Book Reader，可自定义快捷键。

            【文档】
             https://gitee.com/wind_invade/chapter_reader/blob/master/README.md
             https://www.cnblogs.com/winddniw/p/15689784.html
        """.trimIndent()
    }
}
