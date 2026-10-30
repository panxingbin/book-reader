package com.bookreader.reader.actions

import com.bookreader.reader.ui.dialogs.FeatureMenuDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow

/**
 * 标题行小齿轮 — the 功能菜单 entry point on the **Read** tool window's title
 * row, next to the "Read" title (registered via [ToolWindow.setTitleActions]).
 *
 * Opens [FeatureMenuDialog]: a modal, searchable list of every feature, the same
 * shape as the 选书 dialog. It replaced the anchored `JBPopup` — there is nothing
 * to "click again to close" any more, the dialog closes with Esc / 取消 or by
 * running an entry.
 */
class ReaderMenuTitleAction(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : AnAction("功能菜单", "功能菜单：在线/本地阅读、网站调试、字体、过滤、历史记录…", AllIcons.General.Gear),
    DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        FeatureMenuDialog(project, toolWindow.component).show()
    }
}
