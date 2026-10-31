package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.bot.generated.UpstreamProviders
import com.lagradost.quicknovel.fixUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

data class ProviderSearchResult(val api: MainAPI, val results: List<SearchResponse>, val error: String?, val millis: Long)

/**
 * Registry of the upstream QuickNovel providers + the logic the app uses around them
 * (rate limiting, 10 min load cache, ad removal of chapter html).
 */
object Providers {
    val all: List<MainAPI> by lazy {
        val list = UpstreamProviders.enabled() +
                (if (Config.includeDisabled) UpstreamProviders.disabled() else emptyList())
        list.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    /** Providers that are only used for direct links (Reddit) */
    private val special: List<MainAPI> by lazy { UpstreamProviders.special() }

    val languages: List<String> by lazy { all.map { it.lang }.distinct().sorted() }

    fun byName(name: String): MainAPI? =
        (all + special).firstOrNull { it.name == name } ?: (all + special).firstOrNull { it.name.equals(name, true) }

    private fun hostOf(url: String): String? =
        url.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")

    /** Finds the provider that owns [url] (same idea as the app's deep link handling) */
    fun forUrl(url: String): MainAPI? {
        val host = hostOf(url) ?: return null
        return (all + special).firstOrNull { api ->
            val h = hostOf(api.mainUrl) ?: return@firstOrNull false
            host == h || host.endsWith(".$h") || h.endsWith(".$host")
        }
    }

    // ------------------------------------------------------------------ search

    suspend fun searchOne(api: MainAPI, query: String): ProviderSearchResult {
        val start = System.currentTimeMillis()
        return try {
            val res = withTimeoutOrNull(Config.searchTimeoutSec * 1000L) {
                rateLimited(api) { api.search(query) }
            }
            val elapsed = System.currentTimeMillis() - start
            when {
                res == null && elapsed >= Config.searchTimeoutSec * 1000L -> ProviderSearchResult(api, emptyList(), "timeout", elapsed)
                else -> ProviderSearchResult(api, res.orEmpty().distinctBy { it.url }, null, elapsed)
            }
        } catch (e: NotImplementedError) {
            ProviderSearchResult(api, emptyList(), "search not supported", System.currentTimeMillis() - start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.debug("search ${api.name} failed", t)
            ProviderSearchResult(api, emptyList(), t.javaClass.simpleName + (t.message?.let { ": ${it.take(80)}" } ?: ""), System.currentTimeMillis() - start)
        }
    }

    /**
     * Searches all [apis] in parallel. [onProgress] is called after every finished provider.
     */
    suspend fun searchAll(
        query: String,
        apis: List<MainAPI>,
        onProgress: suspend (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): List<ProviderSearchResult> = coroutineScope {
        var done = 0
        var found = 0
        val lock = kotlinx.coroutines.sync.Mutex()
        apis.map { api ->
            async {
                val r = searchOne(api, query)
                lock.withLock {
                    done++; found += r.results.size
                    runCatching { onProgress(done, apis.size, found) }
                }
                r
            }
        }.awaitAll()
    }

    /** Orders results so that the best title matches come first, interleaving providers */
    fun rank(query: String, results: List<ProviderSearchResult>): List<SearchResponse> {
        val q = normalize(query)
        val qWords = q.split(' ').filter { it.isNotBlank() }.toSet()
        fun score(s: SearchResponse, pos: Int): Double {
            val n = normalize(s.name)
            var sc = when {
                n == q -> 100.0
                n.startsWith(q) -> 70.0
                n.contains(q) -> 55.0
                else -> {
                    val words = n.split(' ').toSet()
                    if (qWords.isEmpty()) 0.0 else 40.0 * qWords.count { it in words } / qWords.size
                }
            }
            sc -= pos * 1.5 // provider's own ranking
            if (s.posterUrl != null) sc += 1
            return sc
        }
        return results.flatMap { r -> r.results.mapIndexed { i, s -> s to score(s, i) } }
            .sortedByDescending { it.second }.map { it.first }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ------------------------------------------------------------------ load

    private data class Cached(val time: Long, val response: LoadResponse)

    private val loadCache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 10 * 60 * 1000L

    suspend fun load(api: MainAPI, url: String, allowCache: Boolean = true): LoadResponse {
        val fixed = api.fixUrl(url)
        val key = api.name + "|" + fixed
        if (allowCache) loadCache[key]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.response }
        val res = rateLimited(api) { api.load(fixed) } ?: throw ErrorLoadingException("No data")
        if (loadCache.size > 200) loadCache.entries.sortedBy { it.value.time }.take(50).forEach { loadCache.remove(it.key) }
        loadCache[key] = Cached(System.currentTimeMillis(), res)
        return res
    }

    /** Same as the app's APIRepository.loadHtml: provider html with ads/scripts removed */
    suspend fun loadHtml(api: MainAPI, url: String): String? {
        val html = rateLimited(api) { api.loadHtml(url) }
        return html?.removeAds()
    }

    suspend fun <T> rateLimited(api: MainAPI, block: suspend () -> T): T {
        if (!api.hasRateLimit) return block()
        return api.rateLimitMutex.withLock {
            try {
                block()
            } finally {
                kotlinx.coroutines.delay(api.rateLimitTime)
            }
        }
    }

    private fun String?.removeAds(): String? {
        if (this.isNullOrBlank()) return null
        return try {
            val document = Jsoup.parse(this)
            document.select("small.ads-title").remove()
            document.select("script").remove()
            document.select("iframe").remove()
            document.select(".adsbygoogle").remove()
            document.body().html()
        } catch (t: Throwable) {
            this
        }
    }
}
