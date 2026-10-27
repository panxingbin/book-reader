package com.bookreader.reader.local

import com.bookreader.reader.model.ChapterInfo
import com.bookreader.reader.util.HtmlText
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader
import java.io.File

/**
 * A parsed epub: its chapter pointers plus each chapter's readable text.
 *
 * The text is flattened eagerly. An epub's chapters are a few KB each, so the
 * cost is trivial, and doing it up front is what makes cover pages and image-only
 * pages harmless: they are recognised here and dropped, instead of surfacing
 * later as a chapter that reads "内容为空".
 *
 * @property skipped number of spine items that carried no text (cover, colophon,
 *   image-only pages) and were therefore not turned into chapters
 */
class EpubBook(
    val title: String,
    val chapters: List<ChapterInfo>,
    private val contents: List<String>,
    val skipped: Int = 0,
) {
    /** Flattened, readable text of chapter [index]. */
    fun content(index: Int): String = contents.getOrElse(index) { "" }

    fun isEmpty(): Boolean = chapters.isEmpty()
}

/**
 * Reads `.epub` files with [EpubReader], the same library the reference plugin
 * bundles.
 *
 * The spine gives reading order; the table of contents supplies chapter titles
 * with a per-resource title as the fallback.
 *
 * Spine items whose flattened text is empty are **skipped rather than reported**:
 * real epubs almost always start with a cover and a title page, and failing on
 * them produced the "解析章节正文失败：内容为空" the reader used to show.
 */
object EpubChapterReader {

    /** Read [path]. Returns null when the file is not a readable epub. */
    fun read(path: String): EpubBook? {
        val file = File(path)
        if (!file.isFile) return null

        val book: Book = runCatching {
            EpubReader().readEpub(file.inputStream().buffered())
        }.getOrNull() ?: return null

        val toc = LinkedHashMap<String, String>()
        // The NCX is the better source where both exist, so it is applied last.
        toc.putAll(navTitles(book))
        toc.putAll(tocTitles(book))

        val chapters = ArrayList<ChapterInfo>()
        val contents = ArrayList<String>()
        var skipped = 0

        for (reference in book.spine.spineReferences) {
            val resource = reference.resource ?: continue
            val html = runCatching { String(resource.data, Charsets.UTF_8) }.getOrNull() ?: continue

            val text = HtmlText.fromHtml(html)
            if (text.isBlank()) {
                skipped++
                continue
            }

            val title = resource.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: toc[resource.href.substringAfterLast('/').substringBefore('#')]
                ?: toc[resource.href]
                ?: "第${chapters.size + 1}章"

            chapters.add(ChapterInfo(index = chapters.size, title = title, url = path, startOffset = -1))
            contents.add(text)
        }

        if (chapters.isEmpty()) return null

        val bookTitle = book.title?.trim().takeIf { !it.isNullOrBlank() } ?: file.nameWithoutExtension
        return EpubBook(bookTitle, chapters, contents, skipped)
    }

    /**
     * Map of `file-name` → title for every entry of the epub's table of contents.
     * epublib resolves nested `navPoint` structures for us.
     */
    private fun tocTitles(book: Book): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val toc = runCatching { book.tableOfContents }.getOrNull() ?: return out

        fun walk(reference: nl.siegmann.epublib.domain.TOCReference) {
            val title = reference.title?.trim()
            val href = reference.resource?.href?.trim()?.substringAfterLast('/')?.substringBefore('#')
            if (!title.isNullOrEmpty() && !href.isNullOrEmpty()) out[href] = title
            for (child in reference.children) walk(child)
        }
        for (reference in runCatching { toc.tocReferences }.getOrDefault(emptyList())) walk(reference)
        return out
    }

    /**
     * Titles from an **EPUB 3 navigation document**.
     *
     * epublib 3.1 ships only `NCXDocument` — there is no `NAVDocument` at all — so an
     * EPUB 3 book that carries `nav.xhtml` and no `toc.ncx` (perfectly legal, and
     * common) gets no table of contents from the library. Without this, chapter
     * titles would fall back to whatever the per-file `<title>` happens to say.
     */
    private fun navTitles(book: Book): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val resources = runCatching { book.resources.all }.getOrDefault(emptyList())

        for (resource in resources) {
            val href = resource.href ?: continue
            if (!href.endsWith(".xhtml", true) && !href.endsWith(".html", true) && !href.endsWith(".htm", true)) {
                continue
            }

            val html = runCatching { String(resource.data, Charsets.UTF_8) }.getOrNull() ?: continue
            // Cheap filter before parsing: only a navigation document carries epub:type.
            if (!html.contains("epub:type", ignoreCase = true)) continue

            val doc = runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull() ?: continue
            val navs = doc.select("nav")
            val scope = navs.firstOrNull { it.attr("epub:type").contains("toc", ignoreCase = true) }
                ?: navs.firstOrNull { it.select("a[href]").size > 0 }
                ?: continue

            for (link in scope.select("a[href]")) {
                val title = link.text().trim()
                val target = link.attr("href").substringAfterLast('/').substringBefore('#').trim()
                if (title.isNotEmpty() && target.isNotEmpty() && !out.containsKey(target)) {
                    out[target] = title
                }
            }
        }
        return out
    }
}
