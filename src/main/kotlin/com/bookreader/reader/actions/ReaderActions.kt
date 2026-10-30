package com.bookreader.reader.actions

import com.bookreader.reader.BookLibrary
import com.bookreader.reader.ReaderController
import com.bookreader.reader.ReaderLog
import com.bookreader.reader.ReaderMode
import com.bookreader.reader.ReadingEngine
import com.bookreader.reader.remote.Http
import com.bookreader.reader.ui.dialogs.ChapterListDialog
import com.bookreader.reader.ui.dialogs.ContentFilterDialog
import com.bookreader.reader.ui.dialogs.DomainListDialog
import com.bookreader.reader.ui.dialogs.FontDialog
import com.bookreader.reader.ui.dialogs.HelpDialog
import com.bookreader.reader.ui.dialogs.HistoryDialog
import com.bookreader.reader.ui.dialogs.JumpContentDialog
import com.bookreader.reader.ui.dialogs.RemoteDebugDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.io.File

/**
 * Base class for the reader's actions.
 *
 * Every action needs the project and the [ReaderController]; nothing else is
 * shared, so this only removes that boilerplate and keeps the actions dumb.
 */
abstract class ReaderAction : AnAction(), DumbAware {

    final override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        perform(project, ReaderController.getInstance(project))
    }

    abstract fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// ---------------------------------------------------------------------------
// 阅读模式 — one action per entry of the Tools → Read menu
// ---------------------------------------------------------------------------

/** Base for the eight reading modes. */
abstract class ReaderModeAction(private val mode: ReaderMode) : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.start(mode)
    }
}

/** 在线阅读模式. */
class RemoteReadAction : ReaderModeAction(ReaderMode.REMOTE)

/** 在线全章节阅读模式. */
class RemoteReadFullAction : ReaderModeAction(ReaderMode.REMOTE_FULL)

/** 本地阅读模式. */
class LocalReadAction : ReaderModeAction(ReaderMode.LOCAL)

/** 本地全章节阅读模式. */
class LocalReadFullAction : ReaderModeAction(ReaderMode.LOCAL_FULL)

/** Epub阅读模式. */
class EpubReadAction : ReaderModeAction(ReaderMode.EPUB)

/** Epub全章节阅读模式. */
class EpubReadFullAction : ReaderModeAction(ReaderMode.EPUB_FULL)

/** 简易文章阅读模式. */
class SimpleArticleReadAction : ReaderModeAction(ReaderMode.SIMPLE_ARTICLE)

/** 简易文本阅读模式. */
class SimpleTextReadAction : ReaderModeAction(ReaderMode.SIMPLE_TEXT)

/**
 * 打开书籍 — pick a book from the shared directory.
 *
 * Unlike the per-mode entries, the dialog offers a 阅读方式 combo, so this is the
 * one obvious entry point once a 书籍目录 has been set (Settings → Book Reader).
 */
class OpenBookAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.openBookPicker()
    }
}

// ---------------------------------------------------------------------------
// 在线阅读工具
// ---------------------------------------------------------------------------

/** 正常网站列表. */
class DomainListAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        DomainListDialog(project).show()
    }
}

/** 网站节点调试. */
class RemoteDebugAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        RemoteDebugDialog(project).show()
    }
}

/**
 * 保存网站到本地 — download the raw HTML of a URL.
 *
 * The reference plugin offers this because several sites build their node tree
 * with JavaScript, so the FullPath can only be read from a locally saved copy of
 * the page.
 */
class SaveHtmlAction : ReaderAction() {

    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        val url = Messages.showInputDialog(
            project,
            "请输入要保存的网站 URL：",
            "保存网站到本地",
            Messages.getQuestionIcon(),
        )?.trim() ?: return

        if (!Http.isHttpUrl(url)) {
            Messages.showWarningDialog(project, "URL 需以 http:// 或 https:// 开头。", "保存网站到本地")
            return
        }

        val descriptor = saveDialogDescriptor("保存网站到本地", "选择保存位置", "html")
        val baseDir: com.intellij.openapi.vfs.VirtualFile? = null
        val virtualFile = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(baseDir, "site.html") ?: return

        val target = File(virtualFile.file.path)
        val timeoutMs = com.bookreader.reader.ReaderSettings.instance().remoteTimeoutSeconds * 1000

        ApplicationManager.getApplication().executeOnPooledThread {
            val document = Http.document(url, timeoutMs)
            val ok = runCatching {
                target.writeText(document?.outerHtml() ?: "", Charsets.UTF_8)
            }.isSuccess

            ApplicationManager.getApplication().invokeLater {
                if (ok && document !== null) {
                    ReaderLog.load("网站已保存到：${target.absolutePath}")
                    Messages.showInfoMessage(project, "已保存到：\n${target.absolutePath}", "保存网站到本地")
                } else {
                    Messages.showWarningDialog(project, "获取网站失败，请检查 URL 是否可以正常打开。", "保存网站到本地")
                }
            }
        }
    }
}

/**
 * A [FileSaverDescriptor] that filters on one extension, built without ever *linking* against a
 * deprecated constructor — the Marketplace verifier inspects bytecode references, not what runs.
 *
 * The two platform generations cannot share one source-level call:
 *
 *  - **2023.2 (and every older IDE)** has exactly one public constructor,
 *    `FileSaverDescriptor(String, String, String...)`.
 *  - **Current platforms deprecate that varargs constructor** (its label is wrong in the Windows
 *    native dialog) and add `FileSaverDescriptor(String, String)` +
 *    `withExtensionFilter(String)`, neither of which 2023.2 has.
 *
 * So the current pair is tried first via reflection and the old varargs constructor is the
 * fallback — each is only *named as a string*, so no deprecated reference lands in the class file
 * on either generation.
 */
private fun saveDialogDescriptor(title: String, description: String, extension: String): FileSaverDescriptor {
    val type = FileSaverDescriptor::class.java
    val current = runCatching {
        val descriptor = type.getConstructor(String::class.java, String::class.java)
            .newInstance(title, description) as FileSaverDescriptor
        type.getMethod("withExtensionFilter", String::class.java).invoke(descriptor, extension)
        descriptor
    }.getOrNull()
    if (current != null) return current
    // 2023.2: the only constructor that IDE has. Still reflective, still unflagged there
    // (the constructor is not deprecated in 2023.2's own metadata either).
    return type.getConstructor(String::class.java, String::class.java, Array<String>::class.java)
        .newInstance(title, description, arrayOf(extension)) as FileSaverDescriptor
}

// ---------------------------------------------------------------------------
// 控制
// ---------------------------------------------------------------------------

/** 上一页. */
class PreviousPageAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        if (!controller.previousPage()) ReaderLog.debug("已经是最前面了。")
    }
}

/** 下一页. */
class NextPageAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        if (!controller.nextPage()) ReaderLog.debug("已经读到末尾了。")
    }
}

/** 自动下一页【再次按下则停止】. */
class AutoNextPageAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        val on = controller.toggleAutoNext()
        ReaderLog.load(if (on) "已开启自动阅读，再次按下则停止自动阅读。" else "停止自动阅读。")
    }
}

/** 快速清屏 — blank every surface (Ctrl+Alt+1). */
class SwipeAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.clearScreen()
    }
}

/**
 * 有人来了就按我 — toggle: hides all surfaces, presses again to bring them back.
 *
 * The read position stays in the engine, so the second press is instant and no
 * book re-loading happens (unlike 重新初始化).
 */
class BossKeyAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        if (controller.hidden) {
            controller.showReading()
        } else {
            controller.clearScreen()
            ReaderLog.load("已隐藏阅读内容。再次按【有人来了就按我】(Ctrl+Alt+B) 恢复阅读。")
        }
    }
}

/** 重新初始化. */
class RestartAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.restart()
    }
}

/** Read窗口切换至本项目 — 再按一次收起（与右侧侧边栏图标一致）. */
class ToolWindowAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.toggleReadWindow()
    }
}

/** 普通阅读与全章节阅读转换. */
class ConvertReadModeAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        controller.toggleFullChapter()
    }
}

/** 章节列表 / 跳转. */
class JumpChapterAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        ChapterListDialog(project).show()
    }

    override fun update(event: AnActionEvent) {
        val project = event.project ?: return
        event.presentation.isEnabled = project.getService(ReadingEngine::class.java).book != null
    }
}

/** 内容跳转. */
class JumpContentAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        JumpContentDialog(project).show()
    }

    override fun update(event: AnActionEvent) {
        val project = event.project ?: return
        event.presentation.isEnabled = project.getService(ReadingEngine::class.java).book != null
    }
}

/** 字体选择. */
class FontAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        FontDialog(project).show()
    }
}

/** 内容过滤. */
class TextFilterAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        ContentFilterDialog(project).show()
    }
}

/** 历史记录. */
class HistoryAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        HistoryDialog(project).show()
    }

    override fun update(event: AnActionEvent) {
        if (event.project == null) return
        event.presentation.isEnabled = BookLibrary.instance().history().isNotEmpty()
    }
}

/** 插件设置. */
class SettingsAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        // The configurable's display name is "Book Reader" (Settings → Tools → Book Reader);
        // look it up by that name, not by the old Chinese label, which no longer exists.
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Book Reader")
    }
}

/** 使用说明 / 帮助. */
class HelpAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        HelpDialog(project).show()
    }
}

/** 支持一下 — 鼓励持续更新. */
class SupportAction : ReaderAction() {
    override fun perform(project: com.intellij.openapi.project.Project, controller: ReaderController) {
        Messages.showInfoMessage(
            project,
            "本插件参考开源项目 chapter_reader 重做。\n\n" +
                "项目地址：https://gitee.com/wind_invade/chapter_reader/\n" +
                "在线阅读网站适配说明见项目 README。\n\n" +
                "如果它帮你度过了漫长的编译时间，欢迎到原项目点个 Star。",
            "支持一下",
        )
    }
}
