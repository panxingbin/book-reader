package com.bookreader.reader.ui.dialogs

import com.bookreader.reader.ReaderLog
import com.bookreader.reader.ReaderSettings
import com.bookreader.reader.remote.RemoteChapterReader
import com.bookreader.reader.remote.SiteConfig
import com.bookreader.reader.remote.SiteConfigRepository
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 正常网站列表 — the sites from the shared configuration that still work.
 *
 * The reference plugin checks every entry against its `checkUrl` in batches and
 * warns that this "一直卡住" for a while. Here the checks run in a small thread
 * pool and stream into the list as they finish, so the dialog is usable
 * immediately and a slow site never blocks the rest.
 */
class DomainListDialog(private val project: Project) : DialogWrapper(project, false) {

    private val repository = SiteConfigRepository.getInstance()
    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private val statusLabel = JBLabel("准备中…")

    private val running = AtomicBoolean(false)
    private val checked = AtomicInteger(0)
    private val usable = AtomicInteger(0)

    private val pool = Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
        Thread(runnable, "book-reader-site-check").apply { isDaemon = true }
    }

    private val sites: Map<String, SiteConfig> get() = repository.all()

    init {
        title = "正常网站列表"
        isModal = false
        init()
        startCheck()
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 16
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount >= 2) openSelected()
            }
        })

        val buttons = JPanel()
        buttons.add(JButton("打开网站").apply { addActionListener { openSelected() } })
        buttons.add(JButton("重新检测").apply { addActionListener { startCheck() } })
        buttons.add(JButton("停止").apply { addActionListener { running.set(false); updateStatus() } })

        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.NORTH)
        south.add(buttons, BorderLayout.SOUTH)

        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(420))
        panel.add(
            JBLabel("<html>以下为 gitee 已配置且节点正常的网站，双击即可在浏览器打开。<br>" +
                "<i>同一个网站无需重复配置；网站结构变动时，修改原配置即可。</i></html>"),
            BorderLayout.NORTH,
        )
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    private fun startCheck() {
        if (!running.compareAndSet(false, true)) return
        model.clear()
        checked.set(0)
        usable.set(0)
        updateStatus()

        ApplicationManager.getApplication().executeOnPooledThread {
            // The site table itself may still need fetching.
            if (!repository.isLoaded) {
                val result = repository.load()
                if (!result.ok) {
                    ApplicationManager.getApplication().invokeLater {
                        running.set(false)
                        statusLabel.text = result.message
                    }
                    return@executeOnPooledThread
                }
            }

            val all = sites
            if (all.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    running.set(false)
                    statusLabel.text = "在线阅读支持站点为空，请前往 gitee 添加相关支持"
                }
                return@executeOnPooledThread
            }

            val timeoutMs = ReaderSettings.instance().remoteTimeoutSeconds.coerceIn(1, 120) * 1000
            ReaderLog.load("读取在线阅读支持站点开始，如需新增网站，请前往菜单【网站节点调试】解析成功后到 gitee 提交 pr。")

            for ((host, config) in all) {
                if (!running.get()) break
                pool.submit {
                    val ok = runCatching { RemoteChapterReader.check(config, timeoutMs) }.getOrDefault(false)
                    checked.incrementAndGet()
                    ApplicationManager.getApplication().invokeLater {
                        if (ok) {
                            usable.incrementAndGet()
                            model.addElement(host)
                        }
                        updateStatus()
                        if (checked.get() >= all.size) running.set(false)
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val total = sites.size
        statusLabel.text = if (!running.get() && checked.get() >= total && total > 0) {
            "检测完成：${checked.get()}/$total 个网站，可用 ${usable.get()} 个"
        } else {
            "检测中：${checked.get()}/$total，可用 ${usable.get()} 个（可继续操作，不必等待）"
        }
    }

    private fun openSelected() {
        val host = list.selectedValue ?: return
        val config = sites[host] ?: return
        if (config.checkUrl.isNotBlank()) BrowserUtil.browse(config.checkUrl)
    }

    override fun dispose() {
        running.set(false)
        pool.shutdownNow()
        super.dispose()
    }

    private companion object {
        const val POOL_SIZE = 8
    }
}
