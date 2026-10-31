package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.core.JsonProcessingException
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
                withFallback(api) { it.search(query) }
            }
            val elapsed = System.currentTimeMillis() - start
            when {
                res == null && elapsed >= Config.searchTimeoutSec * 1000L -> {
                    markFailure(api); ProviderSearchResult(api, emptyList(), "timeout", elapsed)
                }
                else -> { markSuccess(api); ProviderSearchResult(api, res.orEmpty().distinctBy { it.url }, null, elapsed) }
            }
        } catch (e: NotImplementedError) {
            ProviderSearchResult(api, emptyList(), "search not supported", System.currentTimeMillis() - start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.debug("search ${api.name} failed", t)
            markFailure(api)
            ProviderSearchResult(api, emptyList(), friendlyError(t), System.currentTimeMillis() - start)
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

    fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ------------------------------------------------------------------ load

    private data class Cached(val time: Long, val response: LoadResponse)

    private val loadCache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 10 * 60 * 1000L

    suspend fun load(api: MainAPI, url: String, allowCache: Boolean = true): LoadResponse {
        val fixed = api.fixUrl(url)
        val key = api.name + "|" + fixed
        if (allowCache) loadCache[key]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.response }
        val res = withFallback(api) { it.load(fixed) } ?: throw ErrorLoadingException("No data")
        if (loadCache.size > 200) loadCache.entries.sortedBy { it.value.time }.take(50).forEach { loadCache.remove(it.key) }
        loadCache[key] = Cached(System.currentTimeMillis(), res)
        return res
    }

    /** Same as the app's APIRepository.loadHtml: provider html with ads/scripts removed */
    suspend fun loadHtml(api: MainAPI, url: String): String? {
        val html = withFallback(api) { it.loadHtml(url) }
        return html?.removeAds()
    }

    /** A provider's main page (browse), with the same fallback logic as search/load */
    suspend fun mainPage(api: MainAPI, page: Int): HeadMainPageResponse = withFallback(api) {
        it.loadMainPage(page, it.mainCategories.firstOrNull()?.second, it.orderBys.firstOrNull()?.second, it.tags.firstOrNull()?.second)
    }

    // ------------------------------------------------------------------ chapters (with redirect following)

    /** Learned "this site moved its chapters to that site" rules: provider name -> (from prefix, to prefix) */
    private val redirectRules = ConcurrentHashMap<String, Pair<String, String>>()

    private fun String?.usable() = !this.isNullOrBlank() && this.length > 40

    /**
     * Loads a chapter. Some sites (e.g. NovelFire) redirect their chapter pages - via HTTP or a
     * small JavaScript "Loading..." page - to a sister site that uses another layout. The
     * provider's own parser then finds nothing. When that happens we follow the redirect and
     * parse the page with the provider that owns the target site, and remember the mapping so
     * the next chapters go there directly.
     */
    suspend fun loadChapter(api: MainAPI, url: String): String? {
        redirectRules[api.name]?.let { (from, to) ->
            if (url.startsWith(from)) {
                val target = to + url.removePrefix(from)
                val tApi = forUrl(target)
                if (tApi != null) {
                    val html = try { loadHtml(tApi, target) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { null }
                    if (html.usable()) return html
                }
            }
        }
        var error: Throwable? = null
        val first = try { loadHtml(api, url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { error = t; null }
        if (first.usable()) return first

        val target = findRedirect(api, url)
        if (target != null) {
            val tApi = forUrl(target)
            if (tApi != null) {
                val html = loadHtml(tApi, target)
                if (html.usable()) {
                    learnRedirect(api, url, target)
                    return html
                }
            }
        }
        if (error != null) throw error
        return first
    }

    private val jsRedirect = listOf(
        Regex("""(?:window\.|document\.|top\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']"""),
        Regex("""location\.(?:replace|assign)\(\s*["']([^"']+)["']"""),
        Regex("""(?i)<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["']?\d+\s*;\s*url=([^"'>\s]+)"""),
    )

    /** Returns the page [url] really points to when it is a redirect to another known site */
    private suspend fun findRedirect(api: MainAPI, url: String): String? = try {
        val res = rateLimited(api) { api.app.get(url, timeout = 30L) }
        val finalUrl = res.url
        val body = res.text
        val inPage = if (body.length < 20_000) jsRedirect.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) } else null
        val target = (inPage?.replace("\\/", "/")?.replace("&amp;", "&")?.let { t ->
            if (t.startsWith("http")) t else finalUrl.toHttpUrlOrNull()?.resolve(t)?.toString()
        } ?: finalUrl).takeIf { it != url }
        target?.takeIf { t -> forUrl(t).let { it != null && (it.name != api.name || hostOf(t) != hostOf(url)) } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.debug("redirect check $url failed", t)
        null
    }

    private fun learnRedirect(api: MainAPI, url: String, target: String) {
        val a = url.substringBefore('?').trimEnd('/')
        val b = target.substringBefore('?').trimEnd('/')
        var n = 0
        while (n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        var suffix = a.takeLast(n)
        suffix = suffix.substring(suffix.indexOf('/').coerceAtLeast(0))
        if (!suffix.startsWith("/") || suffix.length < 3) return
        val rule = a.dropLast(suffix.length) to b.dropLast(suffix.length)
        if (redirectRules.put(api.name, rule) != rule) Logger.info("${api.name}: chapters redirect ${rule.first} -> ${rule.second}")
    }

    // ------------------------------------------------------------------ fallbacks

    /**
     * Alternative upstream implementations of the same site. Royal Road's app API is blocked
     * for many servers (Cloudflare 1005 / HTML instead of JSON), the website scraper still works.
     * Loaded by reflection so a renamed/removed upstream class never breaks the build.
     */
    private val fallbackClasses = mapOf(
        "Royal Road" to listOf("com.lagradost.quicknovel.providers.RoyalRoadWebProvider"),
    )

    private val fallbacks: Map<String, List<MainAPI>> by lazy {
        fallbackClasses.mapValues { (_, classes) ->
            classes.mapNotNull { c ->
                runCatching { Class.forName(c).getDeclaredConstructor().newInstance() as MainAPI }
                    .onFailure { Logger.debug("fallback $c not available", it) }.getOrNull()
            }
        }.filterValues { it.isNotEmpty() }
    }

    /** Provider name -> fallback that worked last time (and until when we keep using it) */
    private val preferredFallback = ConcurrentHashMap<String, Pair<MainAPI, Long>>()

    suspend fun <T> withFallback(api: MainAPI, block: suspend (MainAPI) -> T): T {
        val alts = fallbacks[api.name].orEmpty()
        if (alts.isEmpty()) return rateLimited(api) { block(api) }
        val preferred = preferredFallback[api.name]?.takeIf { it.second > System.currentTimeMillis() }?.first
        val order = if (preferred != null) listOf(preferred) + (listOf(api) + alts).filter { it !== preferred } else listOf(api) + alts
        var firstError: Throwable? = null
        for (candidate in order) {
            try {
                val r = rateLimited(candidate) { block(candidate) }
                if (candidate !== api) preferredFallback[api.name] = candidate to System.currentTimeMillis() + 30 * 60_000L
                else preferredFallback.remove(api.name)
                return r
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: NotImplementedError) {
                if (firstError == null) firstError = e
            } catch (t: Throwable) {
                Logger.debug("${api.name} (${candidate.javaClass.simpleName}) failed, trying next implementation", t)
                if (firstError == null) firstError = t
            }
        }
        throw firstError ?: ErrorLoadingException("No data")
    }

    // ------------------------------------------------------------------ health

    private data class Health(var errors: Int = 0, var pausedUntil: Long = 0)

    private val health = ConcurrentHashMap<String, Health>()

    private fun markFailure(api: MainAPI) {
        val h = health.getOrPut(api.name) { Health() }
        synchronized(h) {
            h.errors++
            // a site that failed 3 searches in a row is skipped for a while, so searches stay fast
            if (h.errors >= 3) h.pausedUntil = System.currentTimeMillis() + minOf(60L, 10L * (h.errors - 2)) * 60_000L
        }
    }

    private fun markSuccess(api: MainAPI) { health.remove(api.name) }

    /** True when the site failed several searches in a row and is skipped for now */
    fun isPaused(api: MainAPI): Boolean = (health[api.name]?.pausedUntil ?: 0L) > System.currentTimeMillis()

    // ------------------------------------------------------------------ errors

    /** Turns scraper exceptions into a short message a user can understand */
    fun friendlyError(t: Throwable): String {
        val msg = t.message.orEmpty()
        val low = msg.lowercase()
        return when {
            t is SocketTimeoutException || t is kotlinx.coroutines.TimeoutCancellationException || low.contains("timed out") ->
                "The site did not respond in time (timeout)."
            t is UnknownHostException || t is ConnectException || low.contains("failed to connect") ->
                "The site is not reachable right now."
            low.contains("unexpected character ('<'") || low.contains("unrecognized token") || low.contains("<!doctype") ->
                "The site sent a web page instead of data — it is blocking the bot (Cloudflare) or changed its layout."
            low.contains("no content to map") -> "The site sent an empty answer."
            t is JsonProcessingException -> "The site changed its data format."
            t is NotImplementedError -> "Not supported by this site."
            low.contains("403") || low.contains("1005") || low.contains("forbidden") -> "The site blocked the request (403)."
            t is ErrorLoadingException && msg.isNotBlank() -> msg.take(150)
            t is NullPointerException || t is IndexOutOfBoundsException || t is NoSuchElementException ->
                "Could not read the page (the site probably changed its layout)."
            else -> (t.javaClass.simpleName + (if (msg.isNotBlank()) ": " + msg.lineSequence().first().take(120) else ""))
        }
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
