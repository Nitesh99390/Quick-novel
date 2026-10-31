package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.StreamResponse
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When a site can not deliver some chapters (blocked, layout changed, chapter removed …) the same
 * novel is very often available on another site (NovelBin, FreeWebNovel, LibRead, NovelFull …).
 * This finds such mirrors and maps chapters between them.
 */
object Mirrors {
    class Mirror(val api: MainAPI, val chapters: List<ChapterData>) {
        /** chapter number ("Chapter 12: …" -> 12) -> chapter */
        val byNumber: Map<Int, ChapterData> by lazy {
            chapters.mapNotNull { c -> number(c.name)?.let { it to c } }.toMap()
        }
    }

    private val chapterNo = Regex("""(?i)(?:chapter|chap|ch|episode|ep|глава|capítulo|capitulo|chapitre|bab|第)\.?\s*#?\s*(\d{1,5})""")
    private val leadingNo = Regex("""^\s*#?(\d{1,5})(?:[\s.:\-–—)]|$)""")

    fun number(name: String): Int? =
        chapterNo.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: leadingNo.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Returns up to [max] other sites (same language) that have a novel with exactly the same
     * title and at least a similar number of chapters, best candidates first.
     */
    suspend fun find(original: MainAPI, novelName: String, chapterCount: Int, max: Int = 3): List<Mirror> {
        val wanted = Providers.normalize(novelName)
        if (wanted.length < 3) return emptyList()
        val apis = Providers.all.filter { it.name != original.name && it.lang == original.lang && !Providers.isPaused(it) }
        val results = withTimeoutOrNull((Config.searchTimeoutSec + 5) * 1000L) { Providers.searchAll(novelName, apis) }.orEmpty()
        val candidates = results.flatMap { r ->
            r.results.filter { Providers.normalize(it.name) == wanted }.take(1).map { r.api to it }
        }
        val mirrors = mutableListOf<Mirror>()
        for ((api, res) in candidates) {
            if (mirrors.size >= max) break
            val loaded = withTimeoutOrNull(40_000L) {
                runCatching { Providers.load(api, res.url) }.getOrNull()
            } as? StreamResponse ?: continue
            if (loaded.data.isEmpty()) continue
            // same novel should have roughly as many chapters (or more, when the mirror is ahead)
            if (chapterCount > 10 && loaded.data.size < chapterCount / 2) continue
            mirrors += Mirror(api, loaded.data)
        }
        Logger.info("Mirrors for \"$novelName\" (${original.name}): ${mirrors.joinToString { "${it.api.name} (${it.chapters.size})" }.ifEmpty { "none" }}")
        return mirrors.sortedByDescending { it.chapters.size }
    }

    /** Finds the chapter of [mirror] that corresponds to chapter [index] (0 based) named like [data] */
    fun match(mirror: Mirror, index: Int, data: ChapterData, originalTotal: Int): ChapterData? {
        number(data.name)?.let { n -> mirror.byNumber[n]?.let { return it } }
        val wanted = Providers.normalize(data.name)
        if (wanted.isNotEmpty()) mirror.chapters.firstOrNull { Providers.normalize(it.name) == wanted }?.let { return it }
        // same position, only when both lists have about the same length
        if (originalTotal > 0 && kotlin.math.abs(mirror.chapters.size - originalTotal) <= maxOf(2, originalTotal / 50)) {
            return mirror.chapters.getOrNull(index)
        }
        return null
    }
}
