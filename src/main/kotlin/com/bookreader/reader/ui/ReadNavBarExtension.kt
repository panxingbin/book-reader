package com.bookreader.reader.ui

import com.intellij.ide.navigationToolbar.DefaultNavBarExtension
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager

/**
 * 导航栏显示样式 — the reference plugin's `ReadNavbar`.
 *
 * The navbar draws the current breadcrumb trail; this extension replaces the text of
 * the **project root** node with the current novel line, so the whole navigation bar
 * reads like a document path while actually being the book. A second extension
 * ([DestructionNavBarExtension]) blanks the other element labels so the file name
 * does not compete with the text.
 *
 * The hijack is strictly conditional: only while 正文显示样式 = 导航栏 **and** the
 * plugin master switch is on **and** there is content to show. At every other time —
 * other styles, plugin off, 快速清屏 — the breadcrumb keeps its ordinary project /
 * path labels. (The old version replaced the project name with the plugin's
 * placeholder in **every** style, forever, because the text never reached the
 * instance the platform renders from — see [setContent].)
 */
class ReadNavBarExtension : DefaultNavBarExtension() {

    override fun getPresentableText(obj: Any?): String? {
        if (!isActive()) return null

        // Only the project root node is hijacked; every other node keeps its own text.
        if (obj !is com.intellij.psi.PsiDirectory) return null
        val isRoot = runCatching {
            obj.virtualFile?.path == obj.project.basePath
        }.getOrDefault(false)

        // Blank content (快速清屏 / boss key / no book) means "look like a normal
        // navbar": returning null lets the real project name through.
        return if (isRoot) content.takeIf { it.isNotBlank() } else null
    }

    companion object {
        /** Initial value, shown only until the first refresh decides otherwise. */
        const val PLACEHOLDER = "Book Reader：菜单 Tools → Read"

        /**
         * The navbar text, shared by EVERY instance of this extension.
         *
         * The platform instantiates this class itself through the `navbar`
         * extension point. With a per-instance field, [setContent] wrote to a
         * private `instance()` copy while the navbar kept rendering the
         * platform's own instance — whose field never changed, so the bar was
         * stuck on the placeholder and never showed a reading line. A
         * companion-level field is the single source of truth for all
         * instances (and all open projects).
         */
        @Volatile
        private var content: String = PLACEHOLDER

        /**
         * Replace the navbar text. An empty string means "show nothing"
         * (快速清屏): the breadcrumb then falls back to its ordinary labels
         * instead of advertising the plugin while the boss is looking.
         */
        fun setContent(text: String) {
            content = text
        }

        /** `true` while a reading line (or the initial placeholder) is on the navbar. */
        fun isShowing(): Boolean = content.isNotBlank()

        /** `true` while 正文显示样式 is 导航栏 and 是否开启插件 is on. */
        fun isActive(): Boolean {
            val settings = com.bookreader.reader.ReaderSettings.instance()
            return settings.enabled &&
                settings.displayStyle == com.bookreader.reader.ReaderSettings.DisplayStyle.NAV_BAR
        }

        /**
         * Ask the navbar to rebuild.
         *
         * The navbar model only refreshes on selection / focus changes, so a page
         * turn would never become visible without a nudge. There is no public API on
         * `IdeFrame` for this, so the navbar panel is located in the frame's
         * component tree and its own update queue is asked to rebuild; a plain
         * repaint is the fallback when the layout is not what we expect.
         */
        fun repaintNavBar(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                val frame = WindowManager.getInstance().getIdeFrame(project) ?: return@invokeLater
                val panel = findNavBarPanel(frame.component)

                if (panel == null) {
                    frame.component?.repaint()
                    return@invokeLater
                }

                val rebuilt = runCatching {
                    val queue = queueOf(panel)
                    queue?.javaClass?.getMethod("queueRebuildUi")?.invoke(queue)
                    true
                }.getOrDefault(false)

                if (!rebuilt) {
                    runCatching {
                        panel.revalidate()
                        panel.repaint()
                    }
                }
            }
        }

        /**
         * The legacy panel's `myUpdateQueue`.
         *
         * A single reflective field read, kept in one place and fully wrapped in
         * `runCatching` by the caller: if a future build renames it the navbar simply
         * falls back to a repaint instead of breaking the reader.
         */
        private fun queueOf(panel: java.awt.Component): Any? =
            runCatching {
                val field = panel.javaClass.getDeclaredField("myUpdateQueue")
                field.isAccessible = true
                field.get(panel)
            }.getOrNull()

        /**
         * Depth-first search for the navbar inside the frame, matched by class name.
         *
         * The legacy panel was exactly `NavBarPanel`; newer platforms renamed or
         * re-implemented it (it is gone in 2026.2), so any component whose class
         * name contains "NavBar" is accepted. `javaClass.simpleName` never triggers
         * class loading, so this stays safe everywhere; when nothing matches the
         * caller falls back to a plain frame repaint.
         */
        private fun findNavBarPanel(component: java.awt.Component?): java.awt.Component? {
            if (component == null) return null
            if (component.javaClass.simpleName.contains("NavBar")) return component
            if (component is java.awt.Container) {
                for (child in component.components) {
                    findNavBarPanel(child)?.let { return it }
                }
            }
            return null
        }
    }
}

/**
 * Blanks the breadcrumb labels while the navbar is being used for reading.
 *
 * The reference ships this as a second `navbar` extension ordered right after
 * [ReadNavBarExtension]; without it the file name sits between the project root and
 * the reader text and the line no longer fits.
 *
 * No opinion at all unless the 导航栏 style is active **and** a line is actually
 * showing — otherwise the breadcrumb must stay completely ordinary (the old version
 * blanked labels in every style, which silently wrecked navigation for everyone).
 */
class DestructionNavBarExtension : DefaultNavBarExtension() {

    override fun getPresentableText(obj: Any?): String? {
        if (!ReadNavBarExtension.isActive() || !ReadNavBarExtension.isShowing()) return null
        // Returning a single space (rather than null) is what actually collapses the
        // label; null would mean "no opinion" and let the real text through.
        return " "
    }
}
