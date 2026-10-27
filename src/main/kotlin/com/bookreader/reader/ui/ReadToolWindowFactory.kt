package com.bookreader.reader.ui

import com.bookreader.reader.actions.ReaderMenuTitleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the **Read** tool window (全章节阅读模式).
 *
 * The window is created lazily, so [ReadPanelHolder] is filled in here and every
 * action reaches the panel through the holder rather than forcing creation.
 *
 * A small gear icon on the title row ([ReaderMenuTitleAction], via
 * [ToolWindow.setTitleActions]) opens the 功能菜单 popup, so the full feature list
 * is one click away without going through `Tools → Read`.
 */
class ReadToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReadPanel(project)
        ReadPanelHolder.set(project, panel)

        val content = ContentFactory.getInstance().createContent(panel, "全章节阅读", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(listOf(ReaderMenuTitleAction(project, toolWindow)))

        panel.refresh()
    }
}
