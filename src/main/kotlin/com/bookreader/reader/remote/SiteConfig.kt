package com.bookreader.reader.remote

import com.alibaba.fastjson.JSONObject

/**
 * One entry of the remote site configuration
 * (`chapter_config-v1.json` on the project's Gitee repository).
 *
 * A configuration is **site-wide**, not per book: `chapterPath` and `contentPath`
 * are valid for every novel on that domain. The keys are exactly the ones the
 * reference plugin uses, so the same file can be pasted in unchanged.
 *
 * ```json
 * "www.example.com": {
 *   "checkUrl": "https://www.example.com/book/12345/",
 *   "chapterPath": ".listmain",
 *   "contentPath": "#content",
 *   "nextUrlPath": "a.next",
 *   "nextPathText": "下一页",
 *   "nextLoop": true,
 *   "nextLoopCnt": 15
 * }
 * ```
 *
 * @property checkUrl      文章目录 URL, used to test whether the site still works
 * @property chapterPath   文章目录节点 — container holding the chapter links
 * @property contentPath   章节内容节点
 * @property titlePath     optional node holding the chapter title
 * @property nextUrlPath   optional 下一页 button node (multi-page chapters)
 * @property nextPathText  the button's text; defaults to `下一页`
 * @property nextLoop      `true` = keep following 下一页 while it matches
 * @property nextLoopCnt   safety cap on the loop (also accepts `nextLoopMax`)
 */
data class SiteConfig(
    var checkUrl: String = "",
    var chapterPath: String = "",
    var contentPath: String = "",
    var titlePath: String = "",
    var nextUrlPath: String = "",
    var nextPathText: String = "",
    var nextLoop: Boolean = false,
    var nextLoopCnt: Int = DEFAULT_LOOP_COUNT,
) {
    /** `下一页` button label actually in use. */
    val nextTextOrDefault: String get() = nextPathText.trim().ifBlank { "下一页" }

    /** `true` when this config has enough information to read a chapter. */
    fun isUsable(): Boolean = chapterPath.isNotBlank() && contentPath.isNotBlank()

    /** `true` when a chapter may span several pages on the site. */
    val hasNextPage: Boolean get() = nextUrlPath.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("checkUrl", checkUrl)
        put("chapterPath", chapterPath)
        put("contentPath", contentPath)
        if (titlePath.isNotBlank()) put("titlePath", titlePath)
        if (nextUrlPath.isNotBlank()) put("nextUrlPath", nextUrlPath)
        if (nextPathText.isNotBlank()) put("nextPathText", nextPathText)
        put("nextLoop", nextLoop)
        put("nextLoopCnt", nextLoopCnt)
    }

    override fun toString(): String = toJson().toJSONString()

    companion object {
        const val DEFAULT_LOOP_COUNT = 15

        fun fromJson(json: JSONObject): SiteConfig = SiteConfig(
            checkUrl = json.getString("checkUrl").orEmpty(),
            chapterPath = json.getString("chapterPath").orEmpty(),
            contentPath = json.getString("contentPath").orEmpty(),
            titlePath = json.getString("titlePath").orEmpty(),
            nextUrlPath = json.getString("nextUrlPath").orEmpty(),
            nextPathText = json.getString("nextPathText").orEmpty(),
            nextLoop = json.getBooleanValue("nextLoop"),
            // Older entries in the shared file use `nextLoopMax` for the same thing.
            nextLoopCnt = json.getIntValue("nextLoopCnt")
                .takeIf { it > 0 }
                ?: json.getIntValue("nextLoopMax").takeIf { it > 0 }
                ?: DEFAULT_LOOP_COUNT,
        )
    }
}
