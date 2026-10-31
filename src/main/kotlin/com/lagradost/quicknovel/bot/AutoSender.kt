package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.bot.Ui.h
import com.lagradost.quicknovel.util.AppUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File

/** One novel that was already delivered by the auto sender */
data class SentNovel(
    val key: String,
    val title: String,
    val api: String,
    val url: String,
    val chapters: Int,
    val time: Long = System.currentTimeMillis(),
)

private data class SentFile(
    val enabled: Boolean? = null,
    val sent: List<SentNovel> = emptyList(),
    /** "api|url" -> failed attempts (novels that could not be downloaded are skipped after a few tries) */
    val failed: Map<String, Int> = emptyMap(),
    val providerIndex: Int = 0,
)

/**
 * Finds new novels on the supported sites **by itself** (popular / latest pages of every site),
 * builds a book and sends it to the configured Telegram chats - no user interaction needed.
 *
 * Every delivered novel is stored in `data/sent.json` (by site+url *and* by normalized title),
 * so the same novel is never scraped or sent again - not even when another site has it too.
 */
class AutoSender(
    /** (chat, file name, bytes, caption, mime, thumbnail) -> sends one document */
    private val sendDocument: suspend (Long, String, ByteArray, String, String, ByteArray?) -> Unit,
    private val botName: () -> String,
    /** overrides AUTO_CHAT_IDS / ADMIN_IDS (used by the CLI test) */
    private val targetOverride: Set<Long>? = null,
) {
    constructor(tg: TelegramApi, botName: () -> String) : this(
        { chat, name, bytes, caption, mime, thumb -> tg.sendDocument(chat, name, bytes, caption, mime, thumb) }, botName
    )

    private val file get() = File(Config.dataDir, "sent.json")
    private val mapper = AppUtils.mapper

    private val sent = LinkedHashMap<String, SentNovel>()   // key -> novel
    private val sentUrls = HashSet<String>()                // "api|url"
    private val failed = HashMap<String, Int>()
    private var providerIndex = 0
    @Volatile var enabled: Boolean = Config.autoSend; private set
    @Volatile var running: String? = null; private set
    @Volatile var lastRun: Long = 0; private set
    @Volatile var nextRun: Long = 0; private set
    @Volatile private var wakeUp = false

    val count get() = synchronized(this) { sent.size }
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
            d.enabled?.let { enabled = it }
            Logger.info("AutoSender: ${sent.size} novels already sent, enabled=$enabled")
        } catch (t: Throwable) {
            Logger.error("Could not read ${f.path}", t)
            f.copyTo(File(f.path + ".broken"), overwrite = true)
        }
    }

    @Synchronized
    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(tmp, SentFile(enabled, sent.values.toList(), HashMap(failed), providerIndex))
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
        } catch (t: Throwable) {
            Logger.error("Saving sent.json failed", t)
        }
    }

    fun setEnabled(on: Boolean) { enabled = on; save(); if (on) wakeUp = true }
    fun runNow() { wakeUp = true }

    /** Title based key: "Shadow Slave" from NovelBin and from NovelFire is the same novel */
    private fun key(title: String) = Providers.normalize(title)

    @Synchronized
    fun isSent(api: String, url: String, title: String) =
        (api + "|" + url) in sentUrls || key(title) in sent

    @Synchronized
    private fun markSent(api: MainAPI, url: String, title: String, chapters: Int) {
        val n = SentNovel(key(title), title, api.name, url, chapters)
        sent[n.key] = n; sentUrls += api.name + "|" + url
        failed.remove(api.name + "|" + url)
        save()
    }

    @Synchronized
    private fun markFailed(api: MainAPI, url: String, forever: Boolean = false) {
        val k = api.name + "|" + url
        failed[k] = if (forever) 99 else (failed[k] ?: 0) + 1
        save()
    }

    @Synchronized
    private fun canTry(api: MainAPI, url: String) = (failed[api.name + "|" + url] ?: 0) < 3

    // ------------------------------------------------------------------ loop

    val targets: Set<Long> get() = targetOverride ?: Config.autoChatIds.ifEmpty { Config.adminIds }

    suspend fun loop() {
        load()
        if (targets.isEmpty()) {
            Logger.warn("AutoSender: no AUTO_CHAT_IDS / ADMIN_IDS configured - auto sending is off")
            return
        }
        Logger.info("AutoSender: every ${Config.autoIntervalMin} min, ${Config.autoNovelsPerRun} novel(s) per run → ${targets.joinToString()}")
        delay(20_000) // let the bot start first
        while (coroutineContext.isActive) {
            if (enabled) {
                lastRun = System.currentTimeMillis()
                repeat(Config.autoNovelsPerRun) {
                    try {
                        if (!sendOne()) Logger.info("AutoSender: no new novel found this run")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Logger.error("AutoSender run failed", t)
                    } finally {
                        running = null
                    }
                }
            }
            nextRun = System.currentTimeMillis() + Config.autoIntervalMin * 60_000L
            wakeUp = false
            while (coroutineContext.isActive && !wakeUp && System.currentTimeMillis() < nextRun) delay(5_000)
        }
    }

    /** Providers that can list novels without a query (popular / latest pages) */
    private fun sources(): List<MainAPI> = Providers.all.filter { api ->
        api.hasMainPage && !Providers.isPaused(api) &&
                (Config.autoLanguages.isEmpty() || api.lang in Config.autoLanguages) &&
                (Config.autoSites.isEmpty() || Config.autoSites.any { it.equals(api.name, true) })
    }

    /** Finds the next novel that was never sent and delivers it. Returns false when nothing new was found. */
    suspend fun sendOne(): Boolean {
        val apis = sources()
        if (apis.isEmpty()) { Logger.warn("AutoSender: no usable site"); return false }
        // round robin over the sites, so every site gets its turn
        for (step in apis.indices) {
            val api = synchronized(this) { apis[(providerIndex++).mod(apis.size)] }
            val candidate = nextCandidate(api) ?: continue
            if (deliver(api, candidate)) return true
        }
        save()
        return false
    }

    /**
     * Looks through the site's popular / latest pages (from page 1, so new releases are found
     * first) for a novel that was not sent yet. Already sent novels are skipped without loading.
     */
    private suspend fun nextCandidate(api: MainAPI): SearchResponse? {
        for (page in 1..Config.autoMaxPages) {
            running = "looking for novels on ${api.name} (page $page)"
            val list = withTimeoutOrNull(60_000L) {
                runCatching { Providers.mainPage(api, page).list }.getOrNull()
            }
            if (list.isNullOrEmpty()) return null // end of the list or the site failed
            list.firstOrNull { !isSent(api.name, it.url, it.name) && canTry(api, it.url) }?.let { return it }
        }
        return null
    }

    private suspend fun deliver(api: MainAPI, s: SearchResponse): Boolean {
        running = "downloading ${s.name} (${api.name})"
        Logger.info("AutoSender: ${s.name} from ${api.name}")
        try {
            val r = Providers.load(api, s.url, allowCache = false)
            // the loaded title can differ from the list title -> check again
            if (isSent(api.name, r.url, r.name) || isSent(api.name, s.url, r.name)) {
                markSentAlias(api, s, r.name); return false
            }
            val all = (r as? StreamResponse)?.data
            if (all.isNullOrEmpty()) { markFailed(api, s.url); return false }
            if (all.size < Config.autoMinChapters) { markFailed(api, s.url, forever = true); return false }
            val limit = if (Config.autoMaxChapters > 0) Config.autoMaxChapters else all.size
            val selected = all.take(limit).mapIndexed { i, c -> i to c }
            var last = 0L
            val (chapters, failedCount) = Downloader.download(api, selected, true, r.name, all) { p ->
                val now = System.currentTimeMillis()
                if (now - last > 30_000) { last = now; running = "downloading ${r.name} (${api.name}) ${p.done}/${p.total}" }
            }
            // a book with more than 10% missing chapters is not worth sending
            if (failedCount > selected.size / 10) {
                Logger.warn("AutoSender: ${r.name}: $failedCount/${selected.size} chapters failed, skipped")
                markFailed(api, s.url); return false
            }
            running = "sending ${r.name}"
            val format = BookFormat.of(Config.autoFormat)
            val coverRaw = Downloader.cover(api, r.posterUrl, r.posterHeaders)
            val meta = BookMeta(r.name, r.author?.trim()?.ifBlank { null }, r.synopsis?.trim(), api.name, r.url,
                r.tags.orEmpty().filter { it.isNotBlank() }, api.lang, coverRaw)
            val thumb = coverRaw?.let { BookBuilder.toJpeg(it, 320, 190_000) }
            val files = BookBuilder.buildVolumes(format, meta, chapters, Config.maxUploadBytes, r.name, all.size)
            val caption = { i: Int ->
                "🆕 <b>${h(Ui.cut(r.name, 200))}</b>\n" +
                        (r.author?.takeIf { it.isNotBlank() }?.let { "✍️ ${h(Ui.cut(it.trim(), 80))}\n" } ?: "") +
                        "📚 Chapters 1–${selected.size}" + (if (selected.size < all.size) " of ${all.size}" else "") +
                        (if (files.size > 1) " · Vol ${i + 1}/${files.size}" else "") +
                        " · ${h(api.name)}\n" +
                        (r.tags?.take(5)?.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#" + it.replace(Regex("[^\\p{L}\\p{N}]"), "") }?.let { "$it\n" } ?: "") +
                        "🤖 @${botName()}"
            }
            var delivered = 0
            for (chat in targets) {
                try {
                    files.forEachIndexed { i, (name, bytes) ->
                        sendDocument(chat, name, bytes, caption(i), format.mime, thumb)
                        Store.stats.bytesSent += bytes.size
                    }
                    delivered++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.error("AutoSender: sending to $chat failed", t)
                }
            }
            if (delivered == 0) return false // telegram problem - try the same novel next time
            markSent(api, s.url, r.name, selected.size)
            if (r.url != s.url) synchronized(this) { sentUrls += api.name + "|" + r.url }
            Store.stats.downloads++; Store.stats.chapters += selected.size; Store.markDirty()
            Logger.info("AutoSender: sent ${r.name} (${selected.size} chapters, ${files.size} file(s))")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.warn("AutoSender: ${s.name} (${api.name}) failed: ${Providers.friendlyError(t)}")
            markFailed(api, s.url)
            return false
        }
    }

    /** List title differs from the real title, which was already sent -> remember the url too */
    @Synchronized
    private fun markSentAlias(api: MainAPI, s: SearchResponse, realTitle: String) {
        sentUrls += api.name + "|" + s.url
        Logger.info("AutoSender: ${s.name} = already sent \"$realTitle\", skipped")
        save()
    }
}
