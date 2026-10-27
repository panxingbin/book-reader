package com.bookreader.reader.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Turns markup into readable prose.
 *
 * jsoup's own `Element.text()` collapses every paragraph into one line, which
 * destroys the reading rhythm, so the tree is walked by hand instead: text nodes
 * are kept, `<br>` and block boundaries become newlines, `<script>`/`<style>`
 * are dropped.
 */
object HtmlText {

    /** Tags whose end implies a line break. */
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "section", "article", "dd", "dt", "pre", "table", "ul", "ol", "hr",
    )

    /** Parse a whole HTML document and flatten its body. */
    fun fromHtml(html: String): String {
        val body = runCatching { Jsoup.parse(html).body() }.getOrNull() ?: return ""
        return flatten(body)
    }

    /** Flatten one element to trimmed, non-empty lines joined by `\n`. */
    fun flatten(element: Element): String {
        val sb = StringBuilder()
        appendNode(element, sb)
        return normalise(sb.toString())
    }

    /** Shared clean-up for text produced by any of the readers. */
    fun normalise(raw: String): String = raw
        .replace('\u00A0', ' ')   // &nbsp;
        .replace('\u3000', ' ')   // ideographic space used for indentation
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private fun appendNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "script" || tag == "style") return
                if (tag == "br") {
                    sb.append('\n')
                    return
                }
                for (child in node.childNodes()) appendNode(child, sb)
                if (tag in BLOCK_TAGS) sb.append('\n')
            }
            else -> Unit
        }
    }
}
