package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

data class DownloadProgress(val done: Int, val failed: Int, val total: Int, val cached: Int, val stage: String? = null)

/**
 * Downloads chapters exactly like the app's BookDownloader2.downloadIndividualChapter
 * (retries with back-off, provider rate limit), with a shared on-disk chapter cache so the same
 * chapters are never fetched twice.
 */
object Downloader {
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun cacheFile(api: MainAPI, url: String): File {
        val h = hash(api.name + "|" + url)
        return File(Config.cacheDir, "chapters/${h.take(2)}/$h.html")
    }

    fun isCached(api: MainAPI, url: String) = cacheFile(api, url).let { it.isFile && it.length() > 0 }

    /** Loads a single chapter's html (cached). Returns null when it could not be loaded. */
    suspend fun chapterHtml(api: MainAPI, data: ChapterData, maxTries: Int = 4, useCache: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val f = cacheFile(api, data.url)
            if (useCache && f.isFile && f.length() > 0) return@withContext f.readText()
            for (i in 0 until maxTries) {
                ensureActive()
                try {
                    val page = Providers.loadChapter(api, data.url)
                    if (!page.isNullOrBlank() && page.length > 40) {
                        f.parentFile.mkdirs()
                        val tmp = File(f.path + ".tmp")
                        tmp.writeText(page); tmp.renameTo(f)
                        return@withContext page
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.debug("chapter ${data.url} try ${i + 1} failed", t)
                }
                delay(1000L * (i + 1))
            }
            null
        }

    /**
     * Downloads [chapters] (index = position in the novel's chapter list) and returns cleaned
     * chapters in order. Chapters that fail in the parallel pass are retried slowly one by one
     * (sites often throttle bursts); chapters that still fail get a short placeholder.
     */
    suspend fun download(
        api: MainAPI,
        chapters: List<Pair<Int, ChapterData>>,
        stripAuthorNotes: Boolean,
        novelName: String? = null,
        allChapters: List<ChapterData>? = null,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): Pair<List<Chapter>, Int> = coroutineScope {
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val cached = chapters.count { isCached(api, it.second.url) }
        val html = arrayOfNulls<String>(chapters.size)
        var lastReport = 0L
        val reportLock = kotlinx.coroutines.sync.Mutex()

        var stage: String? = null
        suspend fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReport < 2500) return
            reportLock.lock()
            try {
                if (force || now - lastReport >= 2500) {
                    lastReport = now
                    runCatching { onProgress(DownloadProgress(done.get(), failed.get(), chapters.size, cached, stage)) }
                }
            } finally {
                reportLock.unlock()
            }
        }

        // pass 1: parallel (providers with a rate limit are serialized by their mutex anyway)
        val parallel = if (api.hasRateLimit) 1 else Config.chapterParallelism
        val sem = Semaphore(parallel)
        val consecutiveFails = AtomicInteger(0)
        chapters.mapIndexed { i, (_, data) ->
            async {
                sem.withPermit {
                    // back off when the site starts refusing requests
                    if (consecutiveFails.get() >= parallel) delay(minOf(8000L, 1500L * consecutiveFails.get()))
                    html[i] = chapterHtml(api, data, maxTries = 2)
                    if (html[i] == null) { failed.incrementAndGet(); consecutiveFails.incrementAndGet() } else consecutiveFails.set(0)
                    done.incrementAndGet()
                    report()
                }
            }
        }.awaitAll()

        // pass 2: slow sequential retry of failed chapters (only when the site works at all -
        // when every chapter failed the site is broken/blocked and retrying just wastes minutes)
        val retry = html.indices.filter { html[it] == null }
        if (retry.isNotEmpty() && retry.size < chapters.size) {
            stage = "retrying ${retry.size} failed chapters slowly"
            report(force = true)
            delay(4000)
            for ((n, i) in retry.withIndex()) {
                ensureActive()
                html[i] = chapterHtml(api, chapters[i].second, maxTries = 3)
                if (html[i] != null) failed.decrementAndGet()
                report(force = n == retry.lastIndex)
                delay(1200L + api.rateLimitTime)
            }
        }

        // pass 3: chapters that still fail are taken from another site that has the same novel
        val missing = html.indices.filter { html[it] == null }
        if (missing.isNotEmpty() && novelName != null) {
            stage = "looking for ${missing.size} missing chapters on other sites"
            report(force = true)
            val total = allChapters?.size ?: 0
            for (mirror in Mirrors.find(api, novelName, total)) {
                ensureActive()
                val todo = html.indices.filter { html[it] == null }
                if (todo.isEmpty()) break
                stage = "fetching ${todo.size} missing chapters from ${mirror.api.name}"
                report(force = true)
                val msem = Semaphore(if (mirror.api.hasRateLimit) 1 else Config.chapterParallelism)
                var mirrorFails = 0
                todo.map { i ->
                    async {
                        msem.withPermit {
                            if (mirrorFails >= 5 && html.none { it != null }) return@withPermit
                            val (index, data) = chapters[i]
                            val target = Mirrors.match(mirror, index, data, total) ?: return@withPermit
                            val page = chapterHtml(mirror.api, target, maxTries = 2)
                            if (page != null) { html[i] = page; failed.decrementAndGet() } else mirrorFails++
                            report()
                        }
                    }
                }.awaitAll()
            }
            stage = null
        }
        report(force = true)

        val results = chapters.mapIndexed { i, (index, data) ->
            val title = data.name.ifBlank { "Chapter ${index + 1}" }
            val raw = html[i]
            val body = if (raw != null) {
                withContext(Dispatchers.Default) { runCatching { ChapterCleaner.clean(title, raw, stripAuthorNotes) }.getOrNull() }
                    ?: "<p>(chapter could not be parsed)</p>"
            } else {
                "<p><i>This chapter could not be downloaded.</i></p><p>${org.jsoup.nodes.Entities.escape(data.url)}</p>"
            }
            Chapter(index, title, body)
        }
        results to failed.get()
    }

    /** Downloads a poster with the provider's headers (incl. cloudflare cookies) */
    suspend fun cover(api: MainAPI, url: String?, headers: Map<String, String>?): ByteArray? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return try {
            val client = if (headers?.containsKey("useCloudflareKiller") == true) MainActivity.appWithInterceptor else MainActivity.app
            val clean = headers.orEmpty().filterKeys { it != "useCloudflareKiller" && it != "useIgnore500" }
            val res = client.get(url, headers = clean, referer = api.mainUrl + "/", timeout = 30L)
            val bytes = res.body.bytes()
            if (!res.isSuccessful || bytes.size < 500) null else bytes
        } catch (t: Throwable) {
            Logger.debug("cover $url failed", t)
            null
        }
    }

    /** Deletes cached chapters older than [Config.cacheDays] */
    fun cleanCache(days: Int = Config.cacheDays): Pair<Int, Long> {
        if (days <= 0) return 0 to 0L
        val limit = System.currentTimeMillis() - days * 24L * 3600 * 1000
        var count = 0
        var bytes = 0L
        File(Config.cacheDir, "chapters").walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < limit) { bytes += f.length(); if (f.delete()) count++ }
            else if (f.isDirectory && f.list()?.isEmpty() == true) f.delete()
        }
        return count to bytes
    }

    fun cacheSize(): Pair<Int, Long> {
        var n = 0; var b = 0L
        File(Config.cacheDir, "chapters").walk().filter { it.isFile }.forEach { n++; b += it.length() }
        return n to b
    }
}
