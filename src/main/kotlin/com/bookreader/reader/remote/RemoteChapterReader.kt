package com.bookreader.reader.remote

import com.bookreader.reader.model.ChapterInfo
import com.bookreader.reader.util.HtmlText
import org.jsoup.nodes.Element

/**
 * Reads a novel from a website using nothing but a [SiteConfig].
 *
 * The pipeline is the same one the reference plugin documents in
 * `在线阅读网站适配.MD`:
 *
 *  1. **章节目录** — fetch the catalogue URL, resolve `chapterPath`, take every
 *     `<a href>` inside it. Each becomes a [ChapterInfo].
 *  2. **章节内容** — fetch a chapter URL, resolve `contentPath`, and turn the
 *     element into plain text (block tags and `<br>` become newlines).
 *  3. **下一页** — when `nextUrlPath` is configured, follow it while its label
 *     still matches `nextPathText` and append the pages, capped by `nextLoopCnt`.
 *
 * Step 3 is what makes sites that split one chapter across several pages work;
 * the text match is the safety net that stops the loop when the site's "下一页"
 * button has silently become a "下一章" link.
 */
object RemoteChapterReader {

    /** A parsed catalogue page: its title plus the chapter pointers found on it. */
    data class RemoteCatalog(val title: String, val chapters: List<ChapterInfo>)

    /** Fetch [catalogUrl] and read both its title and its chapter list. */
    fun catalog(catalogUrl: String, config: SiteConfig, timeoutMs: Int): RemoteCatalog {
        val doc = Http.document(catalogUrl, timeoutMs) ?: return RemoteCatalog("", emptyList())
        return RemoteCatalog(doc.title().trim(), chaptersOf(doc, config))
    }

    /**
     * Parse the chapter list of the book whose catalogue is at [catalogUrl].
     * Returns an empty list when the URL is not a catalogue page or the site's
     * markup no longer matches [config].
     */
    fun chapters(catalogUrl: String, config: SiteConfig, timeoutMs: Int): List<ChapterInfo> =
        catalog(catalogUrl, config, timeoutMs).chapters

    /** Chapter pointers found in an already fetched catalogue document. */
    fun chaptersOf(doc: Element, config: SiteConfig): List<ChapterInfo> {
        val container = NodePath.select(doc, config.chapterPath)
            ?: NodePath.selectAll(doc, config.chapterPath).firstOrNull()
            ?: return emptyList()

        val links = container.select("a[href]").ifEmpty {
            // A few entries point at the list's parent instead of the list itself.
            // jsoup's `parent()`/`parentNode()` are self-typed generics that Kotlin
            // cannot compare, so the result is cast before use.
            val parent = container.parentNode() as? Element
            parent?.select("a[href]") ?: doc.select("a[href]")
        }

        val seen = HashSet<String>()
        val out = ArrayList<ChapterInfo>(links.size)
        for (link in links) {
            val title = link.text().trim()
            val href = absoluteHref(link)
            if (title.isEmpty() || href.isEmpty()) continue
            if (href.startsWith("javascript:", true)) continue
            if (!seen.add(href)) continue
            out.add(ChapterInfo(index = out.size, title = title, url = href))
        }
        return out
    }

    /**
     * Fetch one chapter and flatten it to text, following 下一页 pages when the
     * site configuration asks for it. Returns null when nothing could be read.
     */
    fun chapterText(chapterUrl: String, config: SiteConfig, timeoutMs: Int): String? {
        var doc = Http.document(chapterUrl, timeoutMs) ?: return null
        val sb = StringBuilder()

        extractContent(doc, config)?.let { sb.append(it) }

        if (config.nextLoop && config.hasNextPage) {
            var loops = 0
            while (loops < config.nextLoopCnt.coerceIn(1, 200)) {
                val nextUrl = nextPageUrl(doc, config) ?: break
                loops++
                doc = Http.document(nextUrl, timeoutMs) ?: break
                val part = extractContent(doc, config)
                if (part.isNullOrBlank()) break
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(part)
            }
        }

        val text = sb.toString().trim()
        return text.ifBlank { null }
    }

    /**
     * Read a chapter for the 在线全章节 mode where only the body matters; kept
     * separate so callers can tell "no content node" from "empty content".
     */
    fun contentOrNull(chapterUrl: String, config: SiteConfig, timeoutMs: Int): String? =
        chapterText(chapterUrl, config, timeoutMs)

    /** Resolve `contentPath` inside [doc] and flatten it. */
    fun extractContent(doc: Element, config: SiteConfig): String? {
        val node = NodePath.select(doc, config.contentPath) ?: return null
        val text = elementToText(node)
        return text.ifBlank { null }
    }

    /**
     * The URL of the site's 下一页 button, or null when it is absent or its label
     * no longer matches the configured `nextPathText`.
     */
    fun nextPageUrl(doc: Element, config: SiteConfig): String? {
        if (config.nextUrlPath.isBlank()) return null
        val node = NodePath.select(doc, config.nextUrlPath) ?: return null
        val link = if (node.tagName().lowercase() == "a") node else node.selectFirst("a[href]") ?: node

        val label = link.text().trim().ifBlank { node.text().trim() }
        if (!label.contains(config.nextTextOrDefault)) return null

        val href = absoluteHref(link)
        return href.ifBlank { null }
    }

    /**
     * `true` when [config] still works against its `checkUrl` — used by the
     * 正常网站列表 dialog to hide sites whose markup has moved on.
     */
    fun check(config: SiteConfig, timeoutMs: Int): Boolean {
        if (config.checkUrl.isBlank()) return false
        val doc = Http.document(config.checkUrl, timeoutMs) ?: return false
        return NodePath.select(doc, config.chapterPath)?.select("a[href]")?.isNotEmpty() == true
    }

    /** Resolve a possibly relative href against the document's base URI. */
    private fun absoluteHref(link: Element): String {
        val abs = link.attr("abs:href")
        if (abs.isNotBlank()) return abs
        val raw = link.attr("href").trim()
        if (raw.isEmpty()) return ""
        return runCatching { java.net.URI(link.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
    }

    /**
     * Flatten an element to readable text: text nodes are kept, `<br>` and block
     * boundaries become newlines, `<script>`/`<style>` are dropped.
     *
     * Delegates to [HtmlText], which the epub reader needs too.
     */
    fun elementToText(element: Element): String = HtmlText.flatten(element)
}
