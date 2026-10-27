package com.bookreader.reader.remote

import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * The on-disk copy of the shared site configuration.
 *
 * It used to live in [com.bookreader.reader.ReaderSettings.remoteConfigJson], which
 * meant a 798 KB JSON document was written into the IDE's `book-reader.xml` —
 * parsed at every startup and rewritten at every settings save. A file under the
 * IDE system directory is the right place for something that large and that only
 * changes when Gitee changes it.
 *
 * Migration: the first load after the change moves whatever is still in the settings
 * into this file and clears the setting, so nobody loses their offline copy.
 */
object SiteConfigCache {

    private const val DIRECTORY = "book-reader"
    private const val FILE_NAME = "site-config-v1.json"

    /** Absolute path of the cache file. */
    fun file(): File = File(PathManager.getSystemPath(), "$DIRECTORY/$FILE_NAME")

    /** The cached configuration text, or null when there is no usable cache. */
    fun read(): String? = runCatching {
        file().takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Store [json]. Failures are ignored: the next load simply falls back to the network. */
    fun write(json: String) {
        runCatching {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeText(json, Charsets.UTF_8)
        }
    }

    fun clear() {
        runCatching { file().delete() }
    }

    /** Size of the cache file in bytes, 0 when absent or unreadable. */
    fun sizeBytes(): Long = runCatching { file().length() }.getOrDefault(0L)
}
