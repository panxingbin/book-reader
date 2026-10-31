package com.bookreader.reader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import org.jdom.Element

/**
 * Application-wide settings for the reader, editable under
 * **Settings → Tools → Book Reader**.
 *
 * The field set mirrors the reference plugin's settings dialog (see
 * `chapter_message_zh.properties` items 148–159), including the cached copy of
 * the remote site configuration so online reading still works without network.
 *
 * State is written by hand as plain JDOM attributes — no serializer wiring, no
 * `@Attribute` annotations, and it survives every IDE version from 2021.1 on.
 * `@State` **must** declare `storages` (an array); the legacy singular `storage`
 * parameter does not exist on all builds and omitting it throws
 * "No storage specified" the first time the service is touched.
 */
@Service(Service.Level.APP)
@State(
    name = "BookReaderSettings",
    storages = [Storage("book-reader.xml")],
)
class ReaderSettings : PersistentStateComponent<Element> {

    /** 是否开启插件 — when off every action is a no-op (the "boss key" master switch). */
    var enabled: Boolean = true

    /** 远程读取超时时间（秒）. */
    var remoteTimeoutSeconds: Int = 10

    /** 设置单行展示最大字数 — display lines are wrapped at this width. */
    var maxCharsPerLine: Int = 30

    /** 正文显示样式 — where the current line is shown (status bar / editor hint / navbar). */
    var displayStyle: DisplayStyle = DisplayStyle.BOTTOM_LEFT

    /** 导航栏最大字数（显示样式为导航栏时生效，建议 <= 45）. */
    var navBarMaxChars: Int = 45

    /** 是否开启尾部行号 — append the display-line number to each line. */
    var showTailLineNumber: Boolean = false

    /** 是否开启调试日志打印. */
    var debugLog: Boolean = false

    /** 是否开启加载日志打印. */
    var loadLog: Boolean = true

    /** 设置清屏文本 — what the status bar shows after 快速清屏. */
    var clearText: String = "正在编译…"

    /** 章节正则 — how a local .txt / .epub chapter title is recognised. */
    var chapterRegex: String = DEFAULT_CHAPTER_REGEX

    /**
     * 是否询问章节正则 — `false` opens a local chapters-mode book directly with the
     * stored [chapterRegex] instead of prompting for it every time.
     */
    var askChapterRegex: Boolean = true

    /** 内容过滤 — one regular expression per line; matching display lines are dropped. */
    var contentFilter: String = ""

    /** 字体选择 — family name, blank means "IDE default". */
    var fontFamily: String = ""

    /** 字体选择 — point size used by the Read window. */
    var fontSize: Int = 14

    /** 字体选择 — `RRGGBB` (no `#`), blank means "follow the theme". */
    var fontColorHex: String = ""

    /** 全章节模式每页显示的行数. `0` = 按窗口高度自适应. */
    var linesPerPage: Int = 30

    /** 自动下一页的间隔（秒）. */
    var autoNextSeconds: Int = 5

    /** 本地缓存的在线阅读配置（gitee 上的 chapter_config-v1.json 内容）. */
    var remoteConfigJson: String = ""

    /** 在线阅读配置的远程地址. */
    var remoteConfigUrl: String = DEFAULT_CONFIG_URL

    // ---- derived values ----------------------------------------------------

    /**
     * Cache for [chapterPattern] / [filterPatterns].
     *
     * Both are read once per chapter and once per display line, so compiling the
     * regexes on every call was measurable: a 3000-chapter book recompiled the
     * 内容过滤 patterns 3000 times while loading. The cache is keyed on the raw
     * string, so editing the setting in the settings page invalidates it
     * automatically.
     */
    @Volatile
    private var chapterPatternSource: String? = null

    @Volatile
    private var chapterPatternCache: Regex? = null

    @Volatile
    private var filterSource: String? = null

    @Volatile
    private var filterCache: List<Regex> = emptyList()

    /** The chapter regex, or null when the user typed an invalid one. */
    fun chapterPattern(): Regex? {
        val source = chapterRegex
        if (source == chapterPatternSource) return chapterPatternCache

        val compiled = source.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { Regex(it) }.getOrNull()
        }
        chapterPatternCache = compiled
        chapterPatternSource = source
        return compiled
    }

    /** Compiled 内容过滤 patterns; blank lines are skipped. */
    fun filterPatterns(): List<Regex> {
        val source = contentFilter
        if (source == filterSource) return filterCache

        val compiled = source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .toList()
        filterCache = compiled
        filterSource = source
        return compiled
    }

    /** The status bar line width, clamped so it never looks like a novel. */
    fun statusBarWidth(): Int = when (displayStyle) {
        DisplayStyle.NAV_BAR -> navBarMaxChars.coerceIn(10, 45)
        else -> maxCharsPerLine.coerceIn(10, 200)
    }

    // ---- persistence -------------------------------------------------------

    override fun getState(): Element {
        val e = Element("bookReaderSettings")
        e.setAttribute("enabled", enabled.toString())
        e.setAttribute("remoteTimeoutSeconds", remoteTimeoutSeconds.toString())
        e.setAttribute("maxCharsPerLine", maxCharsPerLine.toString())
        e.setAttribute("displayStyle", displayStyle.name)
        e.setAttribute("navBarMaxChars", navBarMaxChars.toString())
        e.setAttribute("showTailLineNumber", showTailLineNumber.toString())
        e.setAttribute("debugLog", debugLog.toString())
        e.setAttribute("loadLog", loadLog.toString())
        e.setAttribute("clearText", clearText)
        e.setAttribute("chapterRegex", chapterRegex)
        e.setAttribute("askChapterRegex", askChapterRegex.toString())
        e.setAttribute("contentFilter", contentFilter)
        e.setAttribute("fontFamily", fontFamily)
        e.setAttribute("fontSize", fontSize.toString())
        e.setAttribute("fontColorHex", fontColorHex)
        e.setAttribute("linesPerPage", linesPerPage.toString())
        e.setAttribute("autoNextSeconds", autoNextSeconds.toString())
        e.setAttribute("remoteConfigUrl", remoteConfigUrl)
        e.setAttribute("remoteConfigJson", remoteConfigJson)
        return e
    }

    override fun loadState(state: Element) {
        state.getAttributeValue("enabled")?.toBooleanStrictOrNull()?.let { enabled = it }
        state.getAttributeValue("remoteTimeoutSeconds")?.toIntOrNull()?.let { remoteTimeoutSeconds = it }
        state.getAttributeValue("maxCharsPerLine")?.toIntOrNull()?.let { maxCharsPerLine = it }
        state.getAttributeValue("displayStyle")?.let { v ->
            DisplayStyle.entries.firstOrNull { it.name == v }?.let { displayStyle = it }
        }
        state.getAttributeValue("navBarMaxChars")?.toIntOrNull()?.let { navBarMaxChars = it }
        state.getAttributeValue("showTailLineNumber")?.toBooleanStrictOrNull()?.let { showTailLineNumber = it }
        state.getAttributeValue("debugLog")?.toBooleanStrictOrNull()?.let { debugLog = it }
        state.getAttributeValue("loadLog")?.toBooleanStrictOrNull()?.let { loadLog = it }
        state.getAttributeValue("clearText")?.let { clearText = it }
        state.getAttributeValue("chapterRegex")?.let { chapterRegex = it }
        state.getAttributeValue("askChapterRegex")?.toBooleanStrictOrNull()?.let { askChapterRegex = it }
        state.getAttributeValue("contentFilter")?.let { contentFilter = it }
        state.getAttributeValue("fontFamily")?.let { fontFamily = it }
        state.getAttributeValue("fontSize")?.toIntOrNull()?.let { fontSize = it }
        state.getAttributeValue("fontColorHex")?.let { fontColorHex = it }
        state.getAttributeValue("linesPerPage")?.toIntOrNull()?.let { linesPerPage = it }
        state.getAttributeValue("autoNextSeconds")?.toIntOrNull()?.let { autoNextSeconds = it }
        state.getAttributeValue("remoteConfigUrl")?.let { remoteConfigUrl = it }
        state.getAttributeValue("remoteConfigJson")?.let { remoteConfigJson = it }
    }

    /** How the 普通 (discreet) mode renders the current line. */
    enum class DisplayStyle(val title: String) {
        /** 状态栏 — the bottom status bar line, the most discreet surface. */
        BOTTOM_LEFT("Status bar (bottom-right text)"),

        /** 编辑器提示 — a grey hint floating over the active editor. */
        CODE_POPUP("Code hint (grey editor bar)"),

        /** 导航栏 — the top breadcrumb row (the "路径栏"). */
        NAV_BAR("Navigation bar (top path bar)"),
    }

    companion object {
        const val DEFAULT_CHAPTER_REGEX = "^第.*章.*"
        const val DEFAULT_CONFIG_URL =
            "https://gitee.com/wind_invade/chapter_reader/raw/master/chapter_config-v1.json"

        fun instance(): ReaderSettings =
            ApplicationManager.getApplication().getService(ReaderSettings::class.java)
                ?: throw IllegalStateException("ReaderSettings service is not available")
    }
}
