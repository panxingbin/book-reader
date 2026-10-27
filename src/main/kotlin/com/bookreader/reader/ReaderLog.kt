package com.bookreader.reader

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger

/**
 * Progress reporting for the reader.
 *
 * Messages are written to the IDE log and, when the matching switch is on, to the
 * notification group `chapter.reader.novel`. That group is declared with
 * `displayType="NONE"`, so nothing ever pops up in the user's face — the text
 * only accumulates in the **Notifications / Event Log** tool window, which is
 * where the reference plugin's "查看打印信息" instructions point.
 */
object ReaderLog {

    const val GROUP_ID = "chapter.reader.novel"

    private val log = Logger.getInstance("com.bookreader.reader")

    /** 加载日志 — parse/load progress. */
    fun load(message: String) {
        log.info(message)
        if (ReaderSettings.instance().loadLog) notify(message)
    }

    /** 调试日志 — verbose detail, off by default. */
    fun debug(message: String) {
        log.debug(message)
        if (ReaderSettings.instance().debugLog) notify(message)
    }

    /** Always reported, regardless of the switches (errors the user must see). */
    fun warn(message: String) {
        log.warn(message)
        notify(message, NotificationType.WARNING)
    }

    fun error(message: String) {
        log.warn(message)
        notify(message, NotificationType.ERROR)
    }

    private fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(message, type)
                .notify(null)
        }
    }
}
