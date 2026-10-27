package com.bookreader.reader.remote

import org.jsoup.nodes.Element

/**
 * Resolves the node paths stored in `chapter_config-v1.json` against a parsed
 * HTML document.
 *
 * The shared file is edited by many people, so it mixes far more notations than
 * the documentation admits. All of these appear in the real 3000-entry file:
 *
 * | notation                          | example                                    |
 * |-----------------------------------|--------------------------------------------|
 * | XPath-ish absolute tag path       | `/html/body/div[6]/div/dl`                 |
 * | relative tag path                 | `div[2]/div[1]/div[3]`                     |
 * | browser "Copy full JS path"       | `html body div.book_last`                  |
 * | plain CSS selector                | `#list`, `.listmain`, `a.dise.sm:nth-of-type(4)` |
 * | CSS segments joined by `/`        | `#content_read/.box_con/.mulu/a[3]`        |
 * | hand-typed typo                   | `/html/body/div[4]/div[2]/div[2]]`          |
 *
 * jsoup understands the plain CSS cases directly; everything else is translated.
 * `div[6]` in XPath means *the 6th `div` among its siblings*, which is exactly
 * CSS `div:nth-of-type(6)` — **not** `:nth-child(6)`.
 *
 * The combinator is chosen by the shape of the segments: `/` between plain tag
 * names is a direct-child chain (`>`), while `/` between selectors that carry a
 * class or id is read as a descendant chain (space), which is what a human typing
 * `.box_con/#list` obviously meant. Both readings are attempted when the first
 * finds nothing.
 */
object NodePath {

    /** A tag segment with an XPath index: `div[6]` (optionally `.cls` after it). */
    private val INDEXED_SEGMENT = Regex("""^(\*|[a-zA-Z][a-zA-Z0-9-]*)\[(\d+)]\s*(.*)$""")

    /** `true` when [raw] looks like an absolute tag path rather than CSS. */
    fun isAbsolutePath(raw: String): Boolean = raw.trim().startsWith("/")

    /**
     * Split [raw] into normalised selector segments, or null when the value
     * cannot be a selector at all (blank, or a full URL pasted by mistake).
     */
    fun segments(raw: String): List<String>? {
        var path = raw.trim()
        if (path.isEmpty()) return null
        // A few entries have a URL where a node path belongs.
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return null

        path = path.trimStart('/').trimEnd('/')
        if (path.isEmpty()) return null

        return path.split('/').filter { it.isNotBlank() }.map { normaliseSegment(it) }
    }

    /** Translate [raw] into a jsoup selector, choosing the combinator by shape. */
    fun toSelector(raw: String): String? {
        val parts = segments(raw) ?: return null
        // `/` between plain tag names is a direct-child chain; between selectors
        // that carry a class or id a human almost certainly meant descendants.
        val combinator = if (parts.any { it.startsWith(".") || it.startsWith("#") }) {
            OTHER_COMBINATOR
        } else {
            DEFAULT_COMBINATOR
        }
        return parts.joinToString(combinator)
    }

    /** Translate [raw] into a jsoup selector using an explicit [combinator]. */
    fun toSelector(raw: String, combinator: String): String? {
        val parts = segments(raw) ?: return null
        return parts.joinToString(combinator)
    }

    /**
     * Find the element [raw] points at, or null.
     *
     * The primary reading is tried first, then the same segments with the other
     * combinator (which covers the `<tbody>` a browser inserts but the raw markup
     * does not contain), and finally the deepest segment alone.
     */
    fun select(root: Element, raw: String): Element? {
        val parts = segments(raw) ?: return null

        val primary = parts.joinToString(DEFAULT_COMBINATOR)
        root.selectFirst(primary)?.let { return it }

        val loose = parts.joinToString(OTHER_COMBINATOR)
        if (loose != primary) {
            root.selectFirst(loose)?.let { return it }
        }

        val tail = parts.last()
        if (tail != parts.first()) root.selectFirst(tail)?.let { return it }

        return null
    }

    /** All elements [raw] points at. */
    fun selectAll(root: Element, raw: String): List<Element> {
        val parts = segments(raw) ?: return emptyList()

        val primary = parts.joinToString(DEFAULT_COMBINATOR)
        root.select(primary).takeIf { it.isNotEmpty() }?.let { return it }

        val loose = parts.joinToString(OTHER_COMBINATOR)
        if (loose != primary) {
            root.select(loose).takeIf { it.isNotEmpty() }?.let { return it }
        }

        val tail = parts.last()
        return if (tail != parts.first()) root.select(tail) else emptyList()
    }

    /**
     * Normalise one path segment.
     *
     * Bracket imbalance is repaired first (`div[2]]` → `div[2]`), then the XPath
     * index becomes a CSS `:nth-of-type()`.
     */
    private fun normaliseSegment(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // Drop extra closing brackets left by a typo in the shared file.
        val open = s.count { it == '[' }
        val close = s.count { it == ']' }
        if (close > open) s = s.dropLast(close - open)

        val match = INDEXED_SEGMENT.matchEntire(s) ?: return s
        val tag = match.groupValues[1]
        val index = match.groupValues[2]
        val rest = match.groupValues[3].trim()
        return "$tag$rest:nth-of-type($index)"
    }

    /** The default combinator between translated path segments. */
    const val DEFAULT_COMBINATOR = " > "

    /** The alternative combinator, used when the primary reading finds nothing. */
    const val OTHER_COMBINATOR = " "
}
