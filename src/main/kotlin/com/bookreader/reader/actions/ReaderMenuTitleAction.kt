package com.bookreader.reader.actions

import com.bookreader.reader.ui.ReadMenuPopup
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * 标题行小齿轮 — the 功能菜单 entry point on the **Read** tool window's title
 * row, next to the "Read" title (registered via [ToolWindow.setTitleActions]).
 *
 * One click pops the feature menu right where the icon was clicked; keyboard
 * fallbacks (no input event) center the popup inside the tool window.
 */
class ReaderMenuTitleAction(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : AnAction("功能菜单", "功能菜单：在线/本地阅读、网站调试、字体、过滤、历史记录…", AllIcons.General.Gear),
    DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        ReadMenuPopup.show(project, toolWindow, clickPoint(event))
    }

    /**
     * Screen coordinates of the title-row icon click, or null for keyboard invocations.
     *
     * `MouseEvent.getX()/getY()` are component-relative, so the point is converted
     * to absolute screen coordinates — `JBPopup.showInScreenCoordinates` expects
     * screen space, and passing the raw component coordinates would drop the popup
     * into the top-left corner of the screen.
     */
    private fun clickPoint(event: AnActionEvent): java.awt.Point? {
        val mouse = event.inputEvent as? MouseEvent ?: return null
        val source = mouse.source as? java.awt.Component ?: return null
        // MouseEvent coordinates are relative to the source component; JBPopup
        // needs absolute screen coordinates, so convert once here.
        val point = java.awt.Point(mouse.x, mouse.y)
        SwingUtilities.convertPointToScreen(point, source)
        return point
    }
}
