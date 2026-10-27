package com.bookreader.reader.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 功能菜单 — the whole plugin on one page.
 *
 * Opened from the small gear icon on the **Read** tool window's title row
 * ([com.bookreader.reader.actions.ReaderMenuTitleAction]), so every mode is one
 * click away without going through `Tools → Read`.
 *
 * The panel is not a second implementation of the menu: each row looks its action
 * up in `ActionManager` by the id registered in `plugin.xml` and fires it, so the
 * popup can never behave differently from the real menu.
 */
class ReadMenuPanel(
    /**
     * Component the action system resolves each action's `DataContext` from — normally the
     * tool window the popup was opened for, which stays attached after the popup closes.
     *
     * [ActionManager.tryToExecute] (the trigger path this panel uses, see [invoke]) takes a
     * `Component` rather than a `DataContext`, so the panel's own component would be useless
     * here: by the time an action fires, `beforeInvoke` has already cancelled the popup.
     */
    private val contextComponent: JComponent,
    /** Called just before an action runs, so the popup can close first. */
    private val beforeInvoke: () -> Unit = {},
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = JBColor.PanelBackground
        border = JBUI.Borders.empty(6, 4)

        for (group in ReaderActionsCatalog.groups) {
            add(groupHeader(group.title))
            for (row in group.rows) add(MenuRow(row))
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }
    }

    private fun groupHeader(title: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 2, 10)
        add(
            JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = JBColor.GRAY
            },
            BorderLayout.WEST,
        )
    }

    /**
     * Invoke the registered action through the platform's own trigger path, so `update()`
     * runs first, disabled rows stay dead, and actions that need `event.project` resolve it
     * from the [contextComponent]'s DataContext exactly as they do from the menu.
     */
    private fun invoke(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        // Two verifier rules collide here:
        //  - AnAction.actionPerformed(AnActionEvent) is @ApiStatus.OverrideOnly: plugins may
        //    override it, never call it, or the Marketplace rejects the build.
        //  - The obvious escape, ActionUtil.invokeAction, is itself @Deprecated on current
        //    platforms, and its replacement ActionUtil.performAction needs an AnActionEvent
        //    built by AnActionEvent.createEvent, which 2023.2 does not have.
        // ActionManager.tryToExecute is the one trigger that is public, unannotated in every
        // release, and as old as the supported range: it runs update(), the enabled check and
        // the action itself, deriving the DataContext from the component we hand it.
        ActionManager.getInstance().tryToExecute(action, null, contextComponent, ActionPlaces.UNKNOWN, true)
    }

    /** One clickable feature. */
    private inner class MenuRow(private val row: ReaderActionsCatalog.Row) : JPanel(BorderLayout()) {

        private val normal = JBColor.PanelBackground
        private val hover = JBColor(ColorHolder.HOVER, ColorHolder.HOVER)

        init {
            isOpaque = true
            background = normal
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(5, 10)

            val icon = JBLabel().apply {
                icon = row.icon
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyRight(8)
            }

            val title = JBLabel(row.title).apply {
                font = font.deriveFont(Font.BOLD)
            }

            val shortcut = JBLabel(row.shortcut).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            }

            val titleRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.WEST)
                add(shortcut, BorderLayout.EAST)
            }

            val description = JBLabel(row.description).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            }

            val text = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(titleRow, BorderLayout.NORTH)
                add(description, BorderLayout.CENTER)
            }

            add(icon, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)

            toolTipText = if (row.shortcut.isBlank()) row.description else "${row.description}  (${row.shortcut})"

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    background = hover
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    background = normal
                    repaint()
                }

                override fun mouseClicked(event: MouseEvent) {
                    if (event.button != MouseEvent.BUTTON1) return
                    // Close the popup first: several of these actions open a modal
                    // dialog, which must not be owned by a popup that is going away.
                    beforeInvoke()
                    ApplicationManager.getApplication().invokeLater { invoke(row.actionId) }
                }
            })
        }
    }

    private object ColorHolder {
        /** A neutral hover tint that works on both light and dark themes. */
        val HOVER: java.awt.Color = JBColor(0xEDF3FE, 0x2F3E52)
    }
}

/** Shows [ReadMenuPanel] as a popup anchored to a component. */
object ReadMenuPopup {

    /**
     * Open the 功能菜单 popup below [anchor].
     *
     * The popup is resizable and scrollable: 26 entries do not fit on a small
     * screen, and a menu that silently cuts entries off is worse than a scrollbar.
     */
    fun show(project: Project, anchor: JComponent) {
        var popup: JBPopup? = null
        val panel = ReadMenuPanel(anchor) { popup?.cancel() }

        val scroll = JBScrollPane(panel).apply {
            border = null
            preferredSize = Dimension(JBUI.scale(430), JBUI.scale(560))
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, null)
            .setTitle("功能菜单")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        popup.show(RelativePoint(anchor, Point(0, anchor.height)))
    }

    /**
     * Open the 功能菜单 popup from the **Read** tool window's title-row gear icon
     * ([com.bookreader.reader.actions.ReaderMenuTitleAction]).
     *
     * [clickPoint] holds the screen coordinates of the icon click — the popup is
     * shown right under the gear. Without them (keyboard invocation) it is
     * centered inside the tool window.
     */
    fun show(project: Project, toolWindow: ToolWindow, clickPoint: java.awt.Point?) {
        var popup: JBPopup? = null
        val panel = ReadMenuPanel(toolWindow.component) { popup?.cancel() }

        val scroll = JBScrollPane(panel).apply {
            border = null
            preferredSize = Dimension(JBUI.scale(430), JBUI.scale(560))
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, null)
            .setTitle("功能菜单")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        val owner = toolWindow.component
        when (val p = clickPoint) {
            is Point -> popup.showInScreenCoordinates(owner, p)
            else -> popup.showInCenterOf(owner)
        }
    }
}
