package com.bookreader.reader.ui

import com.bookreader.reader.ReaderSettings
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * 普通阅读模式 — where the current line actually lands.
 *
 * The display style (正文显示样式) decides the surface:
 *
 * | 正文显示样式 | 实现 |
 * |---|---|
 * | 状态栏（右下角） | 状态栏 widget（[ReaderStatusBar]）|
 * | 编辑器提示（灰底浮层） | `HintManager.showInformationHint(editor, text)` |
 * | 导航栏（顶部路径栏） | the `navbar` extension point (`NavBarModelExtension`) |
 *
 * This object is the single place that decides which surface gets the text, so
 * the styles never disagree.
 */
object ReaderSurface {

    /** Show [text] using the style configured in 正文显示样式. */
    fun show(project: Project, editor: Editor?, text: String) {
        val style = ReaderSettings.instance().displayStyle
        when (style) {
            // 状态栏（右下角）样式由 ReaderStatusBar.update 直接写 widget，这里不重复。
            ReaderSettings.DisplayStyle.BOTTOM_LEFT -> Unit

            // 编辑器提示：在编辑器里盖一条灰色浮层。
            ReaderSettings.DisplayStyle.CODE_POPUP -> showHint(editor, text)

            ReaderSettings.DisplayStyle.NAV_BAR -> {
                // The navbar renders itself from the extension; repaint it so the new
                // text appears without waiting for a selection change. Truncate to
                // 导航栏单行最大字数 so the breadcrumb row still reads like a path.
                val width = ReaderSettings.instance().statusBarWidth()
                ReadNavBarExtension.setContent(text.take(width))
                ReadNavBarExtension.repaintNavBar(project)
            }
        }
    }

    /** Hide everything that can be showing a line. */
    fun hide(project: Project) {
        runCatching { HintManager.getInstance().hideAllHints() }
        ReadNavBarExtension.setContent("")
        ReadNavBarExtension.repaintNavBar(project)
    }

    /**
     * The grey hint over the editor (编辑器提示 样式).
     *
     * `showInformationHint` needs a live editor; when the user is looking at
     * something without one (Welcome screen, a tool window in focus) the hint is
     * skipped rather than throwing.
     */
    private fun showHint(editor: Editor?, text: String) {
        if (editor == null || editor.isDisposed) return
        runCatching { HintManager.getInstance().showInformationHint(editor, text) }
    }

    /** The editor of the currently focused frame, if any. */
    fun activeEditor(project: Project): Editor? {
        val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        manager.selectedTextEditor?.takeIf { !it.isDisposed }?.let { return it }

        // No file open in this project: fall back to any editor that is on screen, so
        // the reading hint still appears over whatever the user is looking at.
        return runCatching {
            com.intellij.openapi.editor.EditorFactory.getInstance().allEditors
                .firstOrNull { !it.isDisposed && it.contentComponent?.isShowing == true }
        }.getOrNull()
    }
}
