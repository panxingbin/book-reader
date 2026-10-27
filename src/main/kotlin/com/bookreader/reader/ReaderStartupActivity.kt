package com.bookreader.reader

import com.bookreader.reader.ui.ReaderStatusBar
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Post-startup wiring for the reader.
 *
 * Two things happen here:
 *
 *  * the status bar widget is subscribed to the engine, so any page turn or
 *    book change refreshes the discreet line even when the **Read** tool window
 *    was never opened;
 *  * the book saved by [ReadingState] is re-opened, but only for local sources —
 *    re-fetching a novel from a website on every project open would be rude to
 *    the site and slow to boot.
 */
class ReaderStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val engine = project.getService(ReadingEngine::class.java)
        engine.addListener {
            ReaderStatusBar.update(project)
        }

        ReaderController.getInstance(project).restoreLast()
    }
}
