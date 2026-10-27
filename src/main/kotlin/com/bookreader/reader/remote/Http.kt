package com.bookreader.reader.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Small HTTP facade over jsoup.
 *
 * Everything goes through jsoup's own connection so cookies, redirects and
 * charset detection behave the same for HTML pages and for the raw JSON config.
 */
object Http {

    /** A desktop browser UA: several novel sites serve a different layout otherwise. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Fetch [url] and parse it as HTML. Returns null on any failure. */
    fun document(url: String, timeoutMs: Int): Document? = runCatching {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(timeoutMs)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .maxBodySize(0)
            .get()
    }.getOrNull()

    /** Fetch [url] and return the raw body as text (used for the JSON config). */
    fun text(url: String, timeoutMs: Int): String? = runCatching {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(timeoutMs)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .maxBodySize(0)
            .execute()
            .body()
    }.getOrNull()

    /** `true` when [url] looks like something jsoup can fetch. */
    fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** `www.example.com` → `example.com`; anything unparsable is returned unchanged. */
    fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
