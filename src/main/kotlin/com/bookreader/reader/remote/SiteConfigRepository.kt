package com.bookreader.reader.remote

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.TypeReference
import com.alibaba.fastjson.JSONObject
import com.bookreader.reader.ReaderLog
import com.bookreader.reader.ReaderSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * Loads and caches the shared site configuration.
 *
 * The file lives in the reference project's Gitee repository and is maintained by
 * its users (one entry per novel site). It is fetched once per session and then
 * kept in memory; a copy is also stored in [ReaderSettings.remoteConfigJson] so
 * online reading keeps working when Gitee is unreachable — the same fallback the
 * reference plugin implements ("将尝试使用本地缓存在线配置").
 *
 * The shared file is **not** strict JSON: several entries carry a trailing comma.
 * fastjson tolerates that by default, which is why it is used here instead of a
 * strict parser.
 */
@Service(Service.Level.APP)
class SiteConfigRepository {

    /** Outcome of a load attempt, so the caller can report something useful. */
    data class LoadResult(
        val ok: Boolean,
        val fromCache: Boolean,
        val count: Int,
        val message: String,
    )

    @Volatile
    private var configs: Map<String, SiteConfig> = emptyMap()

    @Volatile
    private var loaded: Boolean = false

    /** Number of site entries currently held in memory. */
    val size: Int get() = configs.size

    val isLoaded: Boolean get() = loaded

    /** Snapshot of every known site, keyed by domain. */
    fun all(): Map<String, SiteConfig> = configs

    /**
     * Load the configuration. Cheap when it already succeeded once, unless
     * [force] is set (the 重新初始化 menu item).
     *
     * Blocking network I/O — never call this on the EDT.
     */
    @Synchronized
    fun load(force: Boolean = false): LoadResult {
        if (loaded && !force) {
            return LoadResult(true, fromCache = false, count = configs.size, message = "已加载 ${configs.size} 项配置")
        }

        val settings = ReaderSettings.instance()
        val timeoutMs = settings.remoteTimeoutSeconds.coerceIn(1, 120) * 1000

        ReaderLog.load("读取在线阅读支持站点开始…")

        val remote = Http.text(settings.remoteConfigUrl, timeoutMs)
        if (remote != null) {
            val parsed = parse(remote)
            if (parsed.isNotEmpty()) {
                configs = parsed
                loaded = true
                SiteConfigCache.write(remote)
                ReaderLog.load("初始化在线阅读支持站点完成，共加载：${parsed.size} 项配置")
                return LoadResult(true, fromCache = false, count = parsed.size, message = "在线配置加载成功，共 ${parsed.size} 项")
            }
            ReaderLog.load("在线阅读支持站点为空，请前往 gitee 添加相关支持")
        } else {
            ReaderLog.load("初始化在线阅读支持站点失败：网络不可达或超时")
        }

        ReaderLog.load("将尝试使用本地缓存在线配置")
        var cache = SiteConfigCache.read()
        if (cache == null) {
            // One-time migration out of the IDE settings: a 798 KB JSON string does
            // not belong in book-reader.xml, which the platform parses at every
            // startup and rewrites at every settings save.
            val legacy = settings.remoteConfigJson
            if (legacy.isNotBlank()) {
                SiteConfigCache.write(legacy)
                settings.remoteConfigJson = ""
                cache = legacy
                ReaderLog.load("已把在线配置从插件设置迁移到独立文件")
            }
        }
        if (cache.isNullOrBlank()) {
            ReaderLog.load("尝试读取本地缓存配置为空，请复制 gitee 配置文件内容 -> 插件设置 -> 设置在线阅读配置")
            return LoadResult(false, fromCache = true, count = 0, message = "本地缓存配置为空，请到插件设置中导入在线配置")
        }

        val cached = parse(cache)
        if (cached.isEmpty()) {
            return LoadResult(false, fromCache = true, count = 0, message = "本地缓存配置无法解析")
        }
        configs = cached
        loaded = true
        ReaderLog.load("本地缓存配置读取成功，共加载：${cached.size} 项配置")
        return LoadResult(true, fromCache = true, count = cached.size, message = "本地缓存加载成功，共 ${cached.size} 项")
    }

    /** Parse a raw configuration document into site entries. */
    fun parse(json: String): Map<String, SiteConfig> {
        // Parse straight into a typed map: iterating a fastjson JSONObject from Kotlin
        // is brittle across fastjson versions, and the shared file's shape never changes.
        val map: Map<String, JSONObject> = runCatching {
            JSON.parseObject(json, object : TypeReference<LinkedHashMap<String, JSONObject>>() {})
        }.getOrNull() ?: return emptyMap()

        val out = LinkedHashMap<String, SiteConfig>()
        for ((key, value) in map) {
            val config = runCatching { SiteConfig.fromJson(value) }.getOrNull() ?: continue
            out[key] = config
        }
        return out
    }

    /**
     * Find the configuration for [url].
     *
     * Entries in the shared file are keyed by whatever host the contributor used,
     * so `www.` and sub-domain differences are tolerated.
     */
    fun configFor(url: String): SiteConfig? {
        val host = Http.hostOf(url) ?: return null

        configs[host]?.let { return it }
        configs["www.$host"]?.let { return it }

        // Sub-domain keys such as `m.example.com` should answer for `example.com`.
        configs.entries.firstOrNull { (key, _) -> key.lowercase().removePrefix("www.") == host }?.let { return it.value }

        // Last resort: a key that is a suffix of the host (`hujian176.xssjw.cc` → `xssjw.cc`).
        configs.entries.firstOrNull { (key, _) ->
            val k = key.lowercase().removePrefix("www.")
            host.endsWith(".$k") || k.endsWith(".$host")
        }?.let { return it.value }

        return null
    }

    /** Replace the in-memory configuration (used by 网站节点调试 / manual paste). */
    fun putLocal(host: String, config: SiteConfig) {
        configs = configs + (host to config)
        loaded = true
    }

    companion object {
        fun getInstance(): SiteConfigRepository =
            ApplicationManager.getApplication().getService(SiteConfigRepository::class.java)
    }
}
