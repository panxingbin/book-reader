package com.bookreader.reader.ui

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Display metadata for the 功能菜单 dialog.
 *
 * The panel is not a second copy of the menu: every row invokes the *same* action
 * id that [com.bookreader.reader.actions.ReaderActions] registers in
 * `META-INF/plugin.xml`, through `ActionManager`. So the panel can never drift
 * away from the real menu — it only mirrors its wording.
 *
 * If a new action is added to `plugin.xml`, it has to be listed here too; the
 * mismatch is harmless (an unknown id simply does nothing).
 */
object ReaderActionsCatalog {

    /** One clickable row. */
    data class Row(
        val actionId: String,
        val title: String,
        val description: String,
        val shortcut: String = "",
        val icon: Icon? = null,
    )

    /** A titled block of rows, mirroring one group of the Tools → Read menu. */
    data class Group(val title: String, val rows: List<Row>)

    val groups: List<Group> = listOf(
        Group(
            title = "插件",
            rows = listOf(
                Row(
                    "BookReader.Settings", "插件设置", "阅读样式、单行字数、超时、字体、在线配置缓存",
                    "Ctrl+Alt+C", AllIcons.General.Settings,
                ),
                Row(
                    "BookReader.Help", "使用说明 / 帮助", "查看全部菜单的用途与快捷键",
                    "", AllIcons.Actions.Help,
                ),
            ),
        ),
        Group(
            title = "在线阅读",
            rows = listOf(
                Row(
                    "BookReader.Remote.Read", "在线阅读模式", "输入章节目录 URL，在状态栏隐蔽阅读",
                    "Ctrl+Alt+G", AllIcons.Actions.Download,
                ),
                Row(
                    "BookReader.Remote.ReadFull", "在线全章节阅读模式", "输入章节目录 URL，在 Read 窗口整本阅读",
                    "Ctrl+Alt+Shift+G", AllIcons.Actions.EditSource,
                ),
                Row(
                    "BookReader.Remote.DomainList", "正常网站列表", "并发检测 gitee 已配置网站是否仍可用",
                    "Ctrl+Alt+D", AllIcons.General.Web,
                ),
                Row(
                    "BookReader.Remote.Debug", "网站节点调试", "调试 chapterPath / contentPath，一键复制配置",
                    "Ctrl+Alt+Shift+R", AllIcons.General.TodoDefault,
                ),
                Row(
                    "BookReader.Remote.SaveHtml", "保存网站到本地", "保存网页便于获取 JS 动态节点的 FullPath",
                    "", AllIcons.General.Print,
                ),
            ),
        ),
        Group(
            title = "本地阅读",
            rows = listOf(
                Row(
                    "BookReader.Local.OpenBook", "打开书籍…", "选目录列书 + 选择阅读方式，一个入口开始读",
                    "Ctrl+Alt+O", AllIcons.Actions.MenuOpen,
                ),
                Row(
                    "BookReader.Local.Read", "本地阅读模式", "选目录列文件（txt）+ 章节正则，在状态栏阅读",
                    "Ctrl+Alt+F", AllIcons.Actions.Download,
                ),
                Row(
                    "BookReader.Local.ReadFull", "本地全章节阅读模式", "选目录列文件（txt）+ 章节正则，在 Read 窗口阅读",
                    "Ctrl+Alt+Shift+F", AllIcons.Actions.EditSource,
                ),
                Row(
                    "BookReader.Local.Epub", "Epub阅读模式", "选目录列文件（epub），在状态栏阅读",
                    "", AllIcons.Actions.Download,
                ),
                Row(
                    "BookReader.Local.EpubFull", "Epub全章节阅读模式", "选目录列文件（epub），在 Read 窗口阅读",
                    "", AllIcons.Actions.EditSource,
                ),
                Row(
                    "BookReader.Local.SimpleArticle", "简易文章阅读模式", "仅按单行展示最大字数切分内容",
                    "", AllIcons.Actions.Preview,
                ),
                Row(
                    "BookReader.Local.SimpleText", "简易文本阅读模式", "仅按文件换行符分行",
                    "", AllIcons.Actions.Preview,
                ),
            ),
        ),
        Group(
            title = "控制",
            rows = listOf(
                Row(
                    "BookReader.Control.BossKey", "有人来了就按我", "隐藏/恢复阅读内容（隐藏后再次按下恢复，无需重新加载）",
                    "Ctrl+Alt+B", AllIcons.General.HideToolWindow,
                ),
                Row(
                    "BookReader.Control.Swipe", "快速清屏", "一键清屏，立即隐藏阅读内容（按 Ctrl+Alt+B 可恢复）",
                    "Ctrl+Alt+1", AllIcons.General.HideToolWindow,
                ),
                Row(
                    "BookReader.Control.Restart", "重新初始化", "重新解析当前书籍",
                    "Ctrl+Alt+R", AllIcons.Actions.Refresh,
                ),
                Row(
                    "BookReader.Control.ToolWindow", "Read窗口切换至本项目", "显示 / 收起右侧 Read 工具窗口",
                    "Ctrl+Alt+W", AllIcons.Actions.Show,
                ),
                Row(
                    "BookReader.Control.Convert", "普通阅读与全章节阅读转换", "在状态栏与 Read 窗口之间切换",
                    "Ctrl+Alt+Z", AllIcons.General.Show_to_implement,
                ),
                Row(
                    "BookReader.Control.Filter", "内容过滤", "按正则丢弃正文里的广告行",
                    "Ctrl+Alt+T", AllIcons.General.Filter,
                ),
                Row(
                    "BookReader.Control.History", "历史记录", "查看并恢复最近的阅读记录（所有窗口共享）",
                    "Ctrl+Alt+H", AllIcons.Vcs.History,
                ),
                Row(
                    "BookReader.Control.Font", "字体选择", "Read 窗口的字体、字号与颜色",
                    "", AllIcons.General.Settings,
                ),
                Row(
                    "BookReader.Control.JumpChapter", "章节列表 / 跳转", "按名称筛选并跳转章节",
                    "Ctrl+Alt+J", AllIcons.Actions.ListFiles,
                ),
                Row(
                    "BookReader.Control.JumpContent", "内容跳转", "跳转到指定行号",
                    "", AllIcons.Actions.Find,
                ),
                Row(
                    "BookReader.Control.Prev", "上一页", "上一页",
                    "Ctrl+Alt+2", AllIcons.Actions.Back,
                ),
                Row(
                    "BookReader.Control.Next", "下一页", "下一页",
                    "Ctrl+Alt+3", AllIcons.Actions.Forward,
                ),
                Row(
                    "BookReader.Control.AutoNext", "自动下一页【再次按下则停止】", "按固定间隔自动翻页",
                    "Ctrl+Alt+4", AllIcons.Actions.Execute,
                ),
            ),
        ),
        Group(
            title = "其他",
            rows = listOf(
                Row(
                    "BookReader.Support", "赞助一下~ 鼓励持续更新~", "关于本插件与原项目",
                    "", AllIcons.General.Balloon,
                ),
            ),
        ),
    )
}
