package com.bookreader.reader

import com.bookreader.reader.ui.ReaderStatusBar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * Post-startup wiring for the reader.
 *
 * Three things happen here:
 *
 *  * the status bar widget is subscribed to the engine, so any page turn or
 *    book change refreshes the discreet line even when the **Read** tool window
 *    was never opened;
 *  * showing the **Read** window (from its stripe icon, the platform's shortcut
 *    or our own action) lifts 快速清屏, so the window never comes back showing
 *    only the "已隐藏" card — that used to read as "the icon did nothing";
 *  * the book is re-opened: this window's own [ReadingState] first, and — for a
 *    window that never opened a book — the shared [BookLibrary] snapshot, so a
 *    new window already has the last book instead of an empty reader. Only local
 *    sources are restored automatically; re-fetching a novel from a website on
 *    every project open would be rude to the site and slow to boot.
 */
class ReaderStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val engine = project.getService(ReadingEngine::class.java)
        engine.addListener {
            ReaderStatusBar.update(project)
        }

        // The platform shows the tool window from its own stripe button, which the
        // plugin cannot intercept; listen for "shown" instead and reveal the text.
        // The connection is disposed together with the project.
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    val mine = ToolWindowManager.getInstance(project)
                        .getToolWindow(ReaderController.TOOL_WINDOW_ID)
                    if (mine == null || mine !== toolWindow) return

                    ReaderController.getInstance(project).revealAfterShow()
                }
            },
        )

        ReaderController.getInstance(project).restoreLast()
    }
}
