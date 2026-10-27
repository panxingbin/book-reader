package com.bookreader.reader.verify

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.TypeReference
import com.bookreader.reader.model.ChapterInfo
import com.bookreader.reader.remote.NodePath
import com.bookreader.reader.remote.RemoteChapterReader
import com.bookreader.reader.remote.SiteConfig
import com.bookreader.reader.util.HtmlText
import com.bookreader.reader.util.LocalChapterSplitter
import com.bookreader.reader.util.TextWrapper
import java.io.File

/**
 * Standalone verification of the reader's pure logic.
 *
 * Run through the `verifyReader` Gradle task. It deliberately touches only
 * classes that do not depend on the IntelliJ Platform, so it can execute on a
 * plain JVM without an IDE.
 *
 * Usage: `verifyReader <path-to-chapter_config-v1.json>`
 */
private var failures = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("PASS  $name")
    } else {
        println("FAIL  $name  $detail")
        failures++
    }
}

fun main(args: Array<String>) {
    val configPath = args.firstOrNull() ?: run {
        println("usage: verifyReader <chapter_config-v1.json>")
        return
    }

    selectorTranslation()
    selectorResolution()
    htmlFlattening()
    textWrapping()
    chapterSplitting()
    realConfiguration(configPath)

    val epubDir = args.getOrNull(1)
    if (epubDir != null) epubReading(epubDir)

    println()
    if (failures == 0) println("ALL VERIFICATIONS PASSED") else println("$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}

/**
 * Reads both generated test epubs (EPUB 2 + NCX, EPUB 3 + nav.xhtml).
 *
 * This is the path that produced "解析章节正文失败：内容为空": chapters were found
 * but flattening the chapter body yielded nothing.
 */
private fun epubReading(dir: String) {
    println("--- EpubChapterReader ---")

    for (name in listOf("out.epub", "out3.epub")) {
        val path = File(dir, name).absolutePath
        if (!File(path).isFile) {
            check("$name exists", false, path)
            continue
        }

        val book = com.bookreader.reader.local.EpubChapterReader.read(path)
        check("$name: parsed", book != null)
        if (book == null) continue

        println("      title=${book.title}  chapters=${book.chapters.size}  skipped=${book.skipped}")
        for (chapter in book.chapters) {
            println("      ${chapter.number}. ${chapter.title}")
        }

        // Both test epubs put a cover and a title page (image-only) at the front of
        // the spine. Before the fix those became chapters 1 and 2, and reading
        // started on the cover → "解析章节正文失败：内容为空".
        check("$name: 3 chapters", book.chapters.size == 3, "got ${book.chapters.size}")
        check("$name: image-only spine items skipped", book.skipped == 2, "got ${book.skipped}")
        check(
            "$name: no empty chapters left",
            book.chapters.indices.all { book.content(it).isNotBlank() },
            "an empty chapter survived",
        )
        check("$name: titles from TOC", book.chapters.map { it.title } == listOf("第一章 起点", "第二章 发展", "第三章 结尾"))

        val first = book.content(0)
        check("$name: first chapter has text", first.isNotBlank(), "empty")
        check("$name: paragraphs flattened", first.contains("\n"), "no newline in: '${first.take(60)}'")
        check("$name: prose kept", first.contains("这是第一章的正文内容"), "got '${first.take(60)}'")
        check("$name: tags stripped", !first.contains("<p>"), "tags leaked: '${first.take(60)}'")
        check("$name: script dropped", !first.contains("ignored"), "script leaked")

        val last = book.content(2)
        check("$name: last chapter readable", last.contains("这是第三章的正文内容"))
    }
}

// ---------------------------------------------------------------------------

private fun selectorTranslation() {
    println("--- NodePath.toSelector ---")

    fun expect(raw: String, expected: String?) {
        val actual = NodePath.toSelector(raw)
        check(
            "toSelector(${raw.ifBlank { "<blank>" }}) = $actual",
            actual == expected,
            "expected $expected",
        )
    }

    expect("/html/body/div[6]/div/dl", "html > body > div:nth-of-type(6) > div > dl")
    expect("/html/body/div[2]/", "html > body > div:nth-of-type(2)")
    expect("/html/body/div/div[4]/div/div[3]", "html > body > div > div:nth-of-type(4) > div > div:nth-of-type(3)")
    expect("html body div.book_last", "html body div.book_last")
    expect(".listmain", ".listmain")
    expect("#list", "#list")
    expect("#content.showtxt", "#content.showtxt")
    expect("a.dise.sm:nth-of-type(4)", "a.dise.sm:nth-of-type(4)")
    expect("div.book_last", "div.book_last")
    expect("", null)
    expect("https://www.81ksw.com/index/106283596/", null)

    // Forms that actually occur in the shared 3000-entry file but that the
    // reference plugin's documentation never mentions.
    expect("div[2]/div[1]/div[3]", "div:nth-of-type(2) > div:nth-of-type(1) > div:nth-of-type(3)")
    expect("#content_read/.box_con/.mulu/a[3]", "#content_read .box_con .mulu a:nth-of-type(3)")
    expect(".box_con/#list", ".box_con #list")
    expect(".box_con/#content", ".box_con #content")
    expect("/html/body/div[4]/div[2]/div[2]]", "html > body > div:nth-of-type(4) > div:nth-of-type(2) > div:nth-of-type(2)")
    expect("html body#read.read div#chaptercontent.Readarea.ReadAjax_content",
        "html body#read.read div#chaptercontent.Readarea.ReadAjax_content")
}

private fun selectorResolution() {
    println("--- NodePath.select / RemoteChapterReader ---")

    val html = """
        <html><body>
          <div>one</div><div>two</div><div>three</div><div>four</div><div>five</div>
          <div class="listmain">
            <dl>
              <dd><a href="/c/1.html">第一章 起点</a></dd>
              <dd><a href="/c/2.html">第二章 发展</a></dd>
              <dd><a href="/c/3.html">第三章 结尾</a></dd>
            </dl>
          </div>
          <div id="content">正文第一段<br>正文第二段</div>
          <p><a id="pb_next" href="/c/2.html">下一页</a></p>
        </body></html>
    """.trimIndent()

    val doc = org.jsoup.Jsoup.parse(html, "https://example.com/book/1/")

    // jsoup's Element exposes `equals(Object)` as a Java platform signature that
    // Kotlin refuses to use in a null comparison, so identity is used here.
    check("select by class", NodePath.select(doc, ".listmain") !== null)
    check("select by absolute path", NodePath.select(doc, "/html/body/div[6]/div/dl") !== null)
    check("select by tag+class", NodePath.select(doc, "div.listmain") !== null)
    check("select by id", NodePath.select(doc, "#content") !== null)
    check("select by descendant chain", NodePath.select(doc, "html body div.listmain") !== null)
    check("select missing returns null", NodePath.select(doc, "#nope") === null)

    val config = SiteConfig(
        checkUrl = "https://example.com/book/1/",
        chapterPath = ".listmain",
        contentPath = "#content",
        nextUrlPath = "#pb_next",
        nextPathText = "下一页",
    )
    val chapters = RemoteChapterReader.chaptersOf(doc, config)
    check("chapter count", chapters.size == 3, "got ${chapters.size}")
    check("chapter title", chapters.firstOrNull()?.title == "第一章 起点", "got ${chapters.firstOrNull()?.title}")
    check("chapter url absolute", chapters.firstOrNull()?.url == "https://example.com/c/1.html", "got ${chapters.firstOrNull()?.url}")

    val content = RemoteChapterReader.extractContent(doc, config)
    check("content flattened", content == "正文第一段\n正文第二段", "got '$content'")

    check("next page url", RemoteChapterReader.nextPageUrl(doc, config) == "https://example.com/c/2.html")

    val wrongLabel = config.copy(nextPathText = "下一章")
    check("next page stops on label mismatch", RemoteChapterReader.nextPageUrl(doc, wrongLabel) == null)
}

private fun htmlFlattening() {
    println("--- HtmlText ---")

    val html = "<div><script>var x=1;</script><p>第一段</p><p>第二段</p><br>第三段</div>"
    val text = HtmlText.fromHtml(html)
    check("script dropped", !text.contains("var x"), text)
    check("paragraphs on separate lines", text == "第一段\n第二段\n第三段", "got '$text'")

    val nbsp = HtmlText.fromHtml("<p>a&nbsp;b</p>")
    check("nbsp normalised", nbsp == "a b", "got '$nbsp'")
}

private fun textWrapping() {
    println("--- TextWrapper ---")

    val wrapped = TextWrapper.wrap("一二三四五六七八九十", 4)
    check("cjk wrap", wrapped == listOf("一二三四", "五六七八", "九十"), "got $wrapped")

    val english = TextWrapper.wrap("hello wonderful world", 10)
    check(
        "ascii word not split",
        english.all { it.length <= 10 } && english.joinToString(" ").replace("  ", " ") == "hello wonderful world".let { it },
        "got $english",
    )

    check("blank lines dropped", TextWrapper.wrap("a\n\n\nb", 10) == listOf("a", "b"))

    val byChars = TextWrapper.splitByChars("abcdefghij", 4)
    check("splitByChars", byChars == listOf("abcd", "efgh", "ij"), "got $byChars")

    val byLines = TextWrapper.splitByLines("l1\r\nl2\r\n\r\nl3")
    check("splitByLines", byLines == listOf("l1", "l2", "l3"), "got $byLines")
}

private fun chapterSplitting() {
    println("--- LocalChapterSplitter ---")

    val text = """
        简介：这是一本测试小说。
        第一章 起点
        正文第一段。
        正文第二段。
        第二章 发展
        正文第三段。
        第一卷 终章
        正文第四段。
    """.trimIndent()

    val pattern = Regex("^第.*章.*")
    val chapters = LocalChapterSplitter.split(text, pattern)
    check("chapter count", chapters.size == 3, "got ${chapters.size} -> ${chapters.map { it.title }}")
    check("first title", chapters.firstOrNull()?.title == "第一章 起点")
    check("mid-line not matched", chapters.none { it.title.contains("正文") })

    val body = LocalChapterSplitter.chapterBody(text, chapters, 0)
    check("body starts at title", body.startsWith("第一章 起点"), "got '${body.take(20)}'")
    check("body stops before next chapter", !body.contains("第二章"), "got '${body.take(80)}'")

    check("pattern that matches nothing", LocalChapterSplitter.split(text, Regex("^第.*节.*")).isEmpty())
}

/**
 * Parse the real 798 KB configuration file that ships with the reference project
 * and report how it survives this implementation's selector translation.
 */
private fun realConfiguration(path: String) {
    println("--- real chapter_config-v1.json ---")

    val file = File(path)
    if (!file.isFile) {
        check("config file exists", false, path)
        return
    }

    val json = file.readText(Charsets.UTF_8)
    val map: Map<String, JSONObject> = JSON.parseObject(
        json,
        object : TypeReference<LinkedHashMap<String, JSONObject>>() {},
    )

    check("config parsed (trailing commas tolerated)", map.isNotEmpty(), "size=${map.size}")
    println("      entries: ${map.size}")

    var usable = 0
    var withNext = 0
    var looped = 0
    var chapterPathUnusable = 0
    var contentPathUnusable = 0
    val unusableHosts = mutableListOf<String>()

    for ((host, node) in map) {
        val config = SiteConfig.fromJson(node)
        if (config.isUsable()) usable++
        if (config.hasNextPage) withNext++
        if (config.nextLoop) looped++

        val chapterSelector = NodePath.toSelector(config.chapterPath)
        val contentSelector = NodePath.toSelector(config.contentPath)
        if (chapterSelector == null) {
            chapterPathUnusable++
            unusableHosts += "$host (chapterPath='${config.chapterPath}')"
        }
        if (contentSelector == null) contentPathUnusable++
    }

    println("      usable: $usable")
    println("      with nextUrlPath: $withNext   nextLoop=true: $looped")
    println("      chapterPath unusable: $chapterPathUnusable   contentPath unusable: $contentPathUnusable")

    check("most entries are usable", usable > map.size * 0.9, "usable=$usable of ${map.size}")
    check("selector translation succeeds for the vast majority", chapterPathUnusable < map.size * 0.05, "$chapterPathUnusable")
    if (unusableHosts.isNotEmpty()) {
        println("      unusable chapterPath examples: ${unusableHosts.take(5)}")
    }

    // Every chapterPath in the shared file must survive being used as a jsoup
    // selector without throwing — a malformed selector would break the dialog.
    var selectorErrors = 0
    val doc = org.jsoup.Jsoup.parse("<html><body><div id='x'><ul><li><a href='/a'>t</a></li></ul></div></body></html>")
    for ((_, node) in map) {
        val config = SiteConfig.fromJson(node)
        for (raw in listOf(config.chapterPath, config.contentPath, config.nextUrlPath)) {
            if (raw.isBlank()) continue
            val selector = NodePath.toSelector(raw) ?: continue
            try {
                doc.select(selector)
            } catch (t: Throwable) {
                selectorErrors++
                if (selectorErrors <= 5) println("      selector rejected: '$selector' from '$raw' (${t.message})")
            }
        }
    }
    check("no selector is rejected by jsoup", selectorErrors == 0, "$selectorErrors rejected")

    // A couple of concrete, well-known entries.
    val sample = map.entries.firstOrNull { it.key.contains("blqukan") }
    if (sample != null) {
        val config = SiteConfig.fromJson(sample.value)
        check("sample entry parsed", config.chapterPath == ".listmain", "got '${config.chapterPath}'")
        check("sample contentPath", config.contentPath == "#content", "got '${config.contentPath}'")
    }

    val loopSample = map.entries.firstOrNull { SiteConfig.fromJson(it.value).nextLoop }
    if (loopSample != null) {
        val config = SiteConfig.fromJson(loopSample.value)
        check("nextLoopCnt defaulted or parsed", config.nextLoopCnt in 1..200, "got ${config.nextLoopCnt}")
    }
}
