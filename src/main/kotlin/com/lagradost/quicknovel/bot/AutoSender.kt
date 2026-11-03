package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.bot.Ui.h
import com.lagradost.quicknovel.util.AppUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job as CJob

/** One novel that was already delivered by the auto scraper */
data class SentNovel(
    val key: String,
    val title: String,
    val api: String,
    val url: String,
    val chapters: Int,
    val time: Long = System.currentTimeMillis(),
)

/** The novel that is being scraped right now (resumed after a restart) */
data class CurrentNovel(val api: String = "", val url: String = "", val title: String = "", val started: Long = System.currentTimeMillis())

private data class SentFile(
    val enabled: Boolean? = null,
    val sent: List<SentNovel> = emptyList(),
    /** "api|url" -> failed attempts (novels that could not be downloaded are skipped after a few tries) */
    val failed: Map<String, Int> = emptyMap(),
    val providerIndex: Int = 0,
    /** provider name -> position in its listings ("listing index:page") */
    val cursors: Map<String, String> = emptyMap(),
    val current: CurrentNovel? = null,
    /** urls / titles that were skipped by the admin or are not usable (never tried again) */
    val skipped: List<String> = emptyList(),
)

/**
 * 24/7 auto scraper.
 *
 * Picks a novel from the popular / latest lists of the allowed sites, scrapes the **complete**
 * novel, sends the book to the dump channel and right after that starts with the next novel -
 * forever, without any interaction.
 *
 * Every delivered novel is stored in `data/sent.json` (by site+url *and* by normalized title),
 * so the same novel is never scraped or sent again - not even when another site has it too.
 * The novel being scraped is stored as well, so after a restart the bot continues with it
 * (already downloaded chapters come from the disk cache).
 */
class AutoSender(
    /** (chat, file name, bytes, caption, mime, thumbnail) -> sends one document */
    private val sendDocument: suspend (Long, String, ByteArray, String, String, ByteArray?) -> TgFile?,
    private val botName: () -> String,
    /** overrides DUMP_CHANNEL_ID / AUTO_CHAT_IDS / ADMIN_IDS (used by the CLI test) */
    private val targetOverride: Set<Long>? = null,
    /** (chat, cover, caption) -> sends the cover + novel info before the files (optional) */
    private val sendPhoto: (suspend (Long, ByteArray, String) -> TgFile?)? = null,
    /** status messages for the admins (optional) */
    private val notifyAdmins: (suspend (String) -> Unit)? = null,
) {
    constructor(tg: TelegramApi, botName: () -> String) : this(
        sendDocument = { chat, name, bytes, caption, mime, thumb -> tg.sendDocumentFile(chat, name, bytes, caption, mime, thumb) },
        botName = botName,
        sendPhoto = { chat, bytes, caption -> tg.sendPhotoFile(chat, bytes, caption, null) },
        notifyAdmins = { text -> Config.adminIds.forEach { id -> runCatching { tg.sendMessage(id, text) } } },
    )

    private val file get() = File(Config.dataDir, "sent.json")
    private val mapper = AppUtils.mapper

    private val sent = LinkedHashMap<String, SentNovel>()   // title key -> novel
    private val sentUrls = HashSet<String>()                // "api|url"
    private val failed = HashMap<String, Int>()
    private val skipped = LinkedHashSet<String>()           // "api|url" or title key
    private val cursors = HashMap<String, Pair<Int, Int>>()  // api -> (listing, page)
    private var providerIndex = 0
    private var current: CurrentNovel? = null

    @Volatile var enabled: Boolean = Config.autoSend; private set
    @Volatile var running: String? = null; private set
    @Volatile var lastSent: Long = 0; private set
    @Volatile var startedAt: Long = 0; private set
    @Volatile private var wakeUp = false
    @Volatile private var job: CJob? = null
    @Volatile private var skipRequested = false
    @Volatile var sentThisSession = 0; private set

    val count get() = synchronized(this) { sent.size }
    val currentTitle get() = synchronized(this) { current?.let { "${it.title} (${it.api})" } }
    fun recent(n: Int): List<SentNovel> = synchronized(this) { sent.values.toList().takeLast(n).reversed() }

    // ------------------------------------------------------------------ persistence

    @Synchronized
    fun load() {
        val f = file
        if (!f.isFile) return
        try {
            val d: SentFile = mapper.readValue(f)
            d.sent.forEach { sent[it.key] = it; sentUrls += it.api + "|" + it.url }
            failed.putAll(d.failed); providerIndex = d.providerIndex
            skipped.addAll(d.skipped)
            d.cursors.forEach { (k, v) ->
                val (a, b) = v.split(':').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
                cursors[k] = a to b.coerceAtLeast(1)
            }
            current = d.current?.takeIf { it.url.isNotBlank() }
            d.enabled?.let { enabled = it }
            Logger.info("AutoScraper: ${sent.size} novels already sent, enabled=$enabled" + (current?.let { ", resuming \"${it.title}\"" } ?: ""))
        } catch (t: Throwable) {
            Logger.error("Could not read ${f.path}", t)
            f.copyTo(File(f.path + ".broken"), overwrite = true)
        }
    }

    @Synchronized
    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                tmp, SentFile(
                    enabled, sent.values.toList(), HashMap(failed), providerIndex,
                    cursors.mapValues { "${it.value.first}:${it.value.second}" }, current, skipped.toList(),
                )
            )
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
        } catch (t: Throwable) {
            Logger.error("Saving sent.json failed", t)
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on; save()
        if (on) wakeUp = true else job?.cancel() // stop the running novel right away (it is resumed on /auto on)
    }

    fun runNow() { wakeUp = true }

    /** Skips the novel that is scraped right now - it is never tried again. Returns its title. */
    fun skipCurrent(): String? {
        val c = synchronized(this) { current } ?: return null
        synchronized(this) {
            skipped += c.api + "|" + c.url
            skipped += key(c.title)
            current = null
            save()
        }
        skipRequested = true
        job?.cancel()
        wakeUp = true
        return c.title
    }

    /** Title based key: "Shadow Slave" from NovelBin and from NovelFull is the same novel */
    private fun key(title: String) = Providers.normalize(title)

    @Synchronized
    fun isSent(api: String, url: String, title: String): Boolean {
        val k = key(title)
        return (api + "|" + url) in sentUrls || k in sent || (api + "|" + url) in skipped || k in skipped
    }

    @Synchronized
    private fun markSent(api: MainAPI, url: String, title: String, chapters: Int) {
        val n = SentNovel(key(title), title, api.name, url, chapters)
        sent[n.key] = n; sentUrls += api.name + "|" + url
        failed.remove(api.name + "|" + url)
        current = null
        save()
    }

    @Synchronized
    private fun markFailed(api: MainAPI, url: String, forever: Boolean = false) {
        val k = api.name + "|" + url
        failed[k] = if (forever) 99 else (failed[k] ?: 0) + 1
        if (current?.url == url) current = null
        save()
    }

    @Synchronized
    private fun canTry(api: MainAPI, url: String) = (failed[api.name + "|" + url] ?: 0) < 3

    /** Removes a novel from the "already sent" list so it can be scraped again */
    @Synchronized
    fun forget(query: String): Int {
        val q = key(query)
        val hits = sent.values.filter { it.key == q || it.url == query.trim() }
        hits.forEach { sent.remove(it.key); sentUrls.remove(it.api + "|" + it.url) }
        skipped.removeAll { it == q || it.endsWith("|" + query.trim()) }
        if (hits.isNotEmpty()) save()
        return hits.size
    }

    // ------------------------------------------------------------------ loop

    val targets: Set<Long> get() = targetOverride ?: Config.autoChatIds.ifEmpty { Config.adminIds }

    /**
     * Runs forever: novel after novel. A novel is completely scraped and sent, then the next one
     * starts after [Config.autoDelaySec] seconds. Only when no site has a new novel the loop
     * waits [Config.autoIdleMin] minutes.
     */
    suspend fun loop() {
        load()
        if (targets.isEmpty()) {
            Logger.warn("AutoScraper: no DUMP_CHANNEL_ID / ADMIN_IDS configured - auto scraping is off")
            return
        }
        startedAt = System.currentTimeMillis()
        Logger.info("AutoScraper: 24/7, sites: ${sources().joinToString { it.name }} → ${targets.joinToString()}")
        delay(15_000) // let the bot start first
        while (coroutineContext.isActive) {
            if (!enabled) { running = null; waitFor(5_000) { enabled }; continue }
            var found = false
            try {
                coroutineScope {
                    val j = launch { found = sendOne() }
                    job = j
                    j.join()
                }
            } catch (e: CancellationException) {
                if (!coroutineContext.isActive) throw e
            } catch (t: Throwable) {
                Logger.error("AutoScraper run failed", t)
            } finally {
                job = null; running = null
            }
            if (skipRequested) { skipRequested = false; continue }
            if (!enabled) continue
            if (found) {
                if (Config.autoDelaySec > 0) waitFor(Config.autoDelaySec * 1000L) { false }
            } else {
                Logger.info("AutoScraper: no new novel found on any site, waiting ${Config.autoIdleMin} min")
                running = "no new novel found - next try in ${Config.autoIdleMin} min"
                synchronized(this) { cursors.clear(); save() } // start the lists from the beginning again (new releases)
                waitFor(Config.autoIdleMin * 60_000L) { false }
            }
        }
    }

    /** Waits up to [ms], or until /auto now or [until] */
    private suspend fun waitFor(ms: Long, until: () -> Boolean) {
        wakeUp = false
        val end = System.currentTimeMillis() + ms
        while (coroutineContext.isActive && !wakeUp && !until() && System.currentTimeMillis() < end) delay(1_000)
        wakeUp = false
    }

    /** Providers that can list novels without a query (popular / latest pages) */
    fun sources(): List<MainAPI> = Providers.all.filter { api ->
        api.hasMainPage &&
                (Config.autoLanguages.isEmpty() || api.lang in Config.autoLanguages) &&
                (Config.autoSites.isEmpty() || Config.autoSites.any { it.equals(api.name, true) })
    }

    /** Different lists of a site: every "order by" of the default category, e.g. popular, latest, completed */
    private fun listings(api: MainAPI): List<String?> = api.orderBys.map { it.second }.ifEmpty { listOf(null) }

    /** Finds the next novel that was never sent and delivers it. Returns false when nothing new was found. */
    suspend fun sendOne(): Boolean {
        // 1. continue the novel that was interrupted (restart / crash / /auto off)
        val resume = synchronized(this) { current }
        if (resume != null) {
            val api = Providers.byName(resume.api)
            if (api != null && !isSent(api.name, resume.url, resume.title) && canTry(api, resume.url)) {
                Logger.info("AutoScraper: resuming ${resume.title} (${api.name})")
                if (deliver(api, SearchResponse(resume.title, resume.url, apiName = api.name))) return true
            } else synchronized(this) { current = null; save() }
        }
        // 2. round robin over the sites, so every site gets its turn
        val apis = sources().filter { !Providers.isPaused(it) }
        if (apis.isEmpty()) { Logger.warn("AutoScraper: no usable site"); return false }
        for (step in apis.indices) {
            val api = synchronized(this) { apis[(providerIndex++).mod(apis.size)] }
            val candidate = nextCandidate(api) ?: continue
            // sent, failed or duplicate: in every case a candidate existed, so the loop goes on
            // immediately with the next novel instead of idling
            deliver(api, candidate)
            return true
        }
        save()
        return false
    }

    /**
     * Walks through the site's lists (popular, latest, completed …) page by page for a novel that
     * was not sent yet. The position is remembered, so the next call continues where this one
     * stopped instead of scanning the same pages again. Already sent novels are skipped without
     * loading them.
     */
    private suspend fun nextCandidate(api: MainAPI): SearchResponse? {
        val lists = listings(api)
        var (li, page) = synchronized(this) { cursors[api.name] } ?: (0 to 1)
        if (li >= lists.size) return null // this site is exhausted until the next reset
        var emptyPages = 0
        var scanned = 0
        while (li < lists.size && scanned < 40) {
            scanned++
            running = "looking for a new novel on ${api.name} (list ${li + 1}/${lists.size}, page $page)"
            val list = withTimeoutOrNull(60_000L) {
                runCatching {
                    Providers.withFallback(api) {
                        it.loadMainPage(page, it.mainCategories.firstOrNull()?.second, lists[li], it.tags.firstOrNull()?.second)
                    }.list
                }.onFailure { Logger.debug("${api.name} main page $page failed", it) }.getOrNull()
            }
            val hit = list?.firstOrNull { !isSent(api.name, it.url, it.name) && canTry(api, it.url) }
            if (hit != null) {
                synchronized(this) { cursors[api.name] = li to page; save() }
                return hit
            }
            if (list == null) {
                // request failed (blocked / down): keep the position and try this site again next round
                if (++emptyPages >= 2) break
                continue
            }
            emptyPages = 0
            // end of this list (empty page / too deep) -> next list of the site
            if (list.isEmpty() || page >= Config.autoMaxPages) { li++; page = 1 } else page++
        }
        synchronized(this) { cursors[api.name] = li to page; save() }
        return null
    }

    private suspend fun deliver(api: MainAPI, s: SearchResponse): Boolean {
        running = "loading ${s.name} (${api.name})"
        Logger.info("AutoScraper: ${s.name} from ${api.name}")
        synchronized(this) { current = CurrentNovel(api.name, s.url, s.name); save() }
        try {
            val r = Providers.load(api, s.url, allowCache = false)
            // the loaded title can differ from the list title -> check again
            if (isSent(api.name, r.url, r.name) || (r.name != s.name && isSent(api.name, s.url, r.name))) {
                markSentAlias(api, s, r.name); return false
            }
            synchronized(this) { current = CurrentNovel(api.name, s.url, r.name); save() }
            val all = (r as? StreamResponse)?.data
            if (all.isNullOrEmpty()) { markFailed(api, s.url); return false }
            if (all.size < Config.autoMinChapters || (Config.autoSkipAbove > 0 && all.size > Config.autoSkipAbove)) {
                Logger.info("AutoScraper: ${r.name} has ${all.size} chapters, skipped")
                markFailed(api, s.url, forever = true); return false
            }
            val limit = if (Config.autoMaxChapters > 0) Config.autoMaxChapters else all.size
            val selected = all.take(limit).mapIndexed { i, c -> i to c }
            val start = System.currentTimeMillis()
            running = "scraping ${r.name} (${api.name}) 0/${selected.size}"
            val (chapters, failedCount) = Downloader.download(api, selected, true, r.name, all) { p ->
                running = "scraping ${r.name} (${api.name}) ${p.done}/${p.total}" +
                        (if (p.failed > 0) ", ${p.failed} failed" else "") + (p.stage?.let { " - $it" } ?: "")
            }
            // a book with too many missing chapters is not worth sending
            if (failedCount * 100 > selected.size * Config.autoMaxFailedPercent) {
                Logger.warn("AutoScraper: ${r.name}: $failedCount/${selected.size} chapters failed, skipped")
                markFailed(api, s.url)
                notifyAdmins?.invoke("⚠️ <b>${h(Ui.cut(r.name, 100))}</b> (${h(api.name)}) skipped: $failedCount/${selected.size} chapters failed.")
                return false
            }
            running = "building book: ${r.name}"
            val format = BookFormat.of(Config.autoFormat)
            val coverRaw = Downloader.cover(api, r.posterUrl, r.posterHeaders)
            val meta = BookMeta(r.name, r.author?.trim()?.ifBlank { null }, r.synopsis?.trim(), api.name, r.url,
                r.tags.orEmpty().filter { it.isNotBlank() }, api.lang, coverRaw)
            val thumb = coverRaw?.let { BookBuilder.toJpeg(it, 320, 190_000) }
            val files = BookBuilder.buildVolumes(format, meta, chapters, Config.maxUploadBytes, r.name, all.size)
            val hashtags = r.tags?.take(6)?.mapNotNull { t -> t.replace(Regex("[^\\p{L}\\p{N}]"), "").takeIf { it.isNotBlank() } }
                ?.joinToString(" ") { "#$it" }?.takeIf { it.isNotBlank() }
            val status = r.status?.name
            val caption = { i: Int ->
                "📖 <b>${h(Ui.cut(r.name, 200))}</b>\n" +
                        (r.author?.takeIf { it.isNotBlank() }?.let { "✍️ ${h(Ui.cut(it.trim(), 80))}\n" } ?: "") +
                        "📚 Chapters 1–${selected.size}" + (if (selected.size < all.size) " of ${all.size}" else " (complete)") +
                        (if (files.size > 1) " · Vol ${i + 1}/${files.size}" else "") + "\n" +
                        "🌐 ${h(api.name)}" + (status?.let { " · $it" } ?: "") + "\n" +
                        (hashtags?.let { "$it\n" } ?: "") +
                        "🤖 @${botName()}"
            }
            val info = "📖 <b>${h(Ui.cut(r.name, 200))}</b>\n" +
                    (r.author?.takeIf { it.isNotBlank() }?.let { "✍️ <b>Author:</b> ${h(Ui.cut(it.trim(), 80))}\n" } ?: "") +
                    "📚 <b>Chapters:</b> ${all.size}" + (status?.let { " · <b>Status:</b> $it" } ?: "") + "\n" +
                    "🌐 <b>Source:</b> ${h(api.name)}\n" +
                    (hashtags?.let { "🏷 $it\n" } ?: "") +
                    (r.synopsis?.trim()?.takeIf { it.isNotBlank() }?.let { "\n<i>${h(Ui.cut(it, 650))}</i>" } ?: "")
            running = "sending ${r.name} (${files.size} file(s))"
            var delivered = 0
            val jpeg = coverRaw?.let { BookBuilder.toJpeg(it, 1200, 4_000_000) ?: it }
            for (chat in targets) {
                try {
                    val photo = sendPhoto
                    var cover: TgFile? = null
                    if (jpeg != null && photo != null) {
                        cover = runCatching { photo(chat, jpeg, Ui.cut(info, 1020)) }
                            .onFailure { Logger.debug("cover to $chat failed", it) }.getOrNull()
                    }
                    val sentFiles = files.mapIndexed { i, (name, bytes) ->
                        val f = sendDocument(chat, name, bytes, caption(i), format.mime, thumb)
                        Store.stats.bytesSent += bytes.size
                        delay(1500) // stay below Telegram's per-chat limits
                        f
                    }
                    delivered++
                    // remember the novel: users can search it and get it from the channel instantly
                    if (delivered == 1 && sentFiles.all { it != null && it.messageId > 0 }) {
                        Library.add(LibNovel(
                            id = Library.newId(), title = r.name, author = r.author?.trim()?.ifBlank { null },
                            tags = r.tags.orEmpty().filter { it.isNotBlank() }.take(15), chapters = selected.size,
                            status = status, source = api.name, url = r.url,
                            synopsis = r.synopsis?.trim()?.take(1500)?.ifBlank { null },
                            chatId = chat, coverMessageId = cover?.messageId, coverFileId = cover?.fileId,
                            messageIds = sentFiles.map { it!!.messageId }, fileIds = sentFiles.mapNotNull { it!!.fileId },
                            fileNames = files.map { it.first }, format = format.name,
                        ))
                        Library.save(force = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.error("AutoScraper: sending to $chat failed", t)
                    notifyAdmins?.invoke("⚠️ Could not send <b>${h(Ui.cut(r.name, 100))}</b> to <code>$chat</code>: <code>${h(Ui.cut(t.message ?: "", 200))}</code>\nIs the bot an admin of the dump channel?")
                }
            }
            if (delivered == 0) { // telegram problem - try the same novel next time (chapters are cached)
                synchronized(this) { current = CurrentNovel(api.name, s.url, r.name); save() }
                delay(60_000)
                return false
            }
            markSent(api, s.url, r.name, selected.size)
            if (r.url != s.url) synchronized(this) { sentUrls += api.name + "|" + r.url; save() }
            lastSent = System.currentTimeMillis(); sentThisSession++
            Store.stats.downloads++; Store.stats.chapters += selected.size; Store.markDirty()
            val mins = (System.currentTimeMillis() - start) / 60_000
            Logger.info("AutoScraper: sent ${r.name} (${selected.size} chapters, ${files.size} file(s), $mins min)")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.warn("AutoScraper: ${s.name} (${api.name}) failed: ${Providers.friendlyError(t)}")
            markFailed(api, s.url)
            return false
        }
    }

    /** List title differs from the real title, which was already sent -> remember the url too */
    @Synchronized
    private fun markSentAlias(api: MainAPI, s: SearchResponse, realTitle: String) {
        sentUrls += api.name + "|" + s.url
        if (current?.url == s.url) current = null
        Logger.info("AutoScraper: ${s.name} = already sent \"$realTitle\", skipped")
        save()
    }
}
