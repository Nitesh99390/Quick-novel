package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.EpubResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.bot.Ui.h
import com.lagradost.quicknovel.extractors.ExtractorApi
import com.lagradost.quicknovel.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job as CJob

class Bot(private val tg: TelegramApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var botUsername: String = ""

    // ------------------------------------------------------------------ in-memory state

    /** A list of novels shown to the user (search results or a provider's main page) */
    private data class Session(
        val id: String,
        val title: String,
        val results: List<SearchResponse>,
        val summary: String = "",
        val browseApi: String? = null,
        val browsePage: Int = 1,
    )

    private val sessions = object : LinkedHashMap<String, Session>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > 300
    }
    private val sessionCounter = AtomicLong(System.currentTimeMillis() % 100000)

    private sealed class Pending {
        data class Range(val ref: String, val format: BookFormat) : Pending()
        object Broadcast : Pending()
    }

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val searching = ConcurrentHashMap.newKeySet<Long>()

    private data class DlJob(
        val id: Int, val userId: Long, val chatId: Long, val title: String,
        var messageId: Long = 0, var job: CJob? = null, @Volatile var started: Boolean = false,
    )

    private val jobIds = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, DlJob>()
    private val downloadSlots = Semaphore(Config.maxConcurrentDownloads)

    private val readerCache = object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 60
    }

    // ------------------------------------------------------------------ main loop

    suspend fun run() {
        var me: JsonNode? = null
        var attempt = 0
        while (me == null) {
            try {
                me = tg.call("getMe")
            } catch (e: TelegramException) {
                if (e.code == 401 || e.code == 404) throw IllegalStateException("BOT_TOKEN is invalid (${e.description})")
                Logger.warn("getMe failed: ${e.message}, retrying…"); delay(minOf(60_000L, 3000L * ++attempt))
            } catch (t: Throwable) {
                Logger.warn("Telegram not reachable (${t.message}), retrying…"); delay(minOf(60_000L, 3000L * ++attempt))
            }
        }
        botUsername = me.path("username").asText()
        Logger.info("Logged in as @$botUsername — ${Providers.all.size} providers ready")
        tg.setCommands(
            listOf(
                "menu" to "🏠 Main menu",
                "search" to "🔍 Search a novel on all sites",
                "browse" to "🧭 Browse popular novels of a site",
                "library" to "⭐ Your saved novels",
                "history" to "🕘 Recently opened novels",
                "settings" to "⚙️ Format, languages & sources",
                "queue" to "📥 Your running downloads",
                "cancel" to "✖️ Cancel your downloads / input",
                "sources" to "🌐 Supported websites",
                "help" to "❓ How to use the bot",
            )
        )
        runCatching { tg.call("deleteWebhook", mapOf("drop_pending_updates" to false)) }

        scope.launch { // periodic persistence + cache cleanup
            var tick = 0
            while (isActive) {
                delay(15_000)
                Store.save()
                if (++tick % 240 == 0) runCatching { Downloader.cleanCache() }
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { Store.save(force = true) })

        var offset = 0L
        while (true) {
            try {
                val updates = tg.getUpdates(offset)
                for (u in updates) {
                    offset = u.path("update_id").asLong() + 1
                    scope.launch { handleUpdate(u) }
                }
            } catch (e: TelegramException) {
                if (e.code == 409) Logger.error("Another instance is polling with this token (409). Retrying…")
                else if (e.code == 401) { Logger.error("BOT_TOKEN is invalid (401)"); throw e }
                else Logger.warn("getUpdates: ${e.message}")
                delay(5000)
            } catch (t: Throwable) {
                Logger.warn("getUpdates failed: ${t.message}")
                delay(3000)
            }
        }
    }

    private suspend fun handleUpdate(u: JsonNode) {
        try {
            when {
                u.has("message") -> onMessage(u.path("message"))
                u.has("callback_query") -> onCallback(u.path("callback_query"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.error("Update ${u.path("update_id").asLong()} failed", t)
            val chat = u.path("message").path("chat").path("id").asLong(0).takeIf { it != 0L }
                ?: u.path("callback_query").path("message").path("chat").path("id").asLong(0).takeIf { it != 0L }
            if (chat != null) runCatching { tg.sendMessage(chat, "⚠️ Something went wrong: <code>${h(Ui.cut(t.message ?: t.javaClass.simpleName, 300))}</code>") }
        }
    }

    private fun isAdmin(id: Long) = id in Config.adminIds

    private fun allowed(id: Long): Boolean =
        isAdmin(id) || ((Config.allowedUsers.isEmpty() || id in Config.allowedUsers) && Store.users[id]?.banned != true)

    private fun displayName(from: JsonNode) =
        listOf(from.path("first_name").asText(""), from.path("last_name").asText("")).joinToString(" ").trim().ifEmpty { null }

    // ------------------------------------------------------------------ messages

    private suspend fun onMessage(m: JsonNode) {
        val chatId = m.path("chat").path("id").asLong()
        val from = m.path("from")
        val userId = from.path("id").asLong()
        val text = m.path("text").asText("").trim()
        val msgId = m.path("message_id").asLong()
        if (text.isEmpty()) return

        if (!allowed(userId)) {
            if (m.path("chat").path("type").asText() == "private")
                tg.sendMessage(chatId, "🔒 You are not allowed to use this bot.\nYour id: <code>$userId</code>")
            return
        }
        val user = Store.user(userId, displayName(from), from.path("username").asText(null))

        if (text.startsWith("/")) {
            val parts = text.split(Regex("\\s+"), limit = 2)
            val cmdRaw = parts[0].substring(1)
            val target = cmdRaw.substringAfter('@', "")
            if (target.isNotEmpty() && !target.equals(botUsername, true)) return
            val cmd = cmdRaw.substringBefore('@').lowercase()
            val arg = parts.getOrNull(1)?.trim().orEmpty()
            pending.remove(userId)
            handleCommand(cmd, arg, chatId, userId, user, msgId)
            return
        }

        // waiting for input?
        when (val p = pending.remove(userId)) {
            is Pending.Range -> { startDownloadFromText(chatId, userId, p, text); return }
            is Pending.Broadcast -> { if (isAdmin(userId)) broadcast(chatId, text); return }
            null -> {}
        }

        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd(')', ']', '>', '.', ',')
        if (url != null) { openUrl(chatId, url); return }
        if (m.path("chat").path("type").asText() != "private") return // in groups only commands & links
        search(chatId, userId, text, msgId)
    }

    private suspend fun handleCommand(cmd: String, arg: String, chatId: Long, userId: Long, user: UserData, msgId: Long) {
        when (cmd) {
            "start" -> {
                if (arg.startsWith("n_")) { Store.resolve(arg.removePrefix("n_"))?.let { openNovel(chatId, arg.removePrefix("n_"), it) }; return }
                welcome(chatId, user)
            }
            "help" -> help(chatId)
            "menu", "home" -> mainMenu(chatId, null)
            "search", "s" -> if (arg.isBlank()) tg.sendMessage(chatId, "🔍 Send me the novel name, e.g.\n<code>/search Shadow Slave</code>\n\nOr just type the name without a command.")
                else search(chatId, userId, arg, msgId)
            "browse", "popular" -> browseMenu(chatId, null, 0)
            "library", "lib" -> listSaved(chatId, null, user, library = true)
            "history" -> listSaved(chatId, null, user, library = false)
            "settings" -> settings(chatId, null, user)
            "sources", "providers" -> sources(chatId)
            "queue" -> queue(chatId, userId)
            "cancel" -> {
                val mine = jobs.values.filter { it.userId == userId }
                mine.forEach { it.job?.cancel() }
                tg.sendMessage(chatId, if (mine.isEmpty()) "Nothing to cancel 👍" else "✖️ Cancelled ${mine.size} download(s).")
            }
            "id" -> tg.sendMessage(chatId, "Your id: <code>$userId</code>")
            // ---------------- admin
            "stats" -> if (isAdmin(userId)) stats(chatId)
            "broadcast" -> if (isAdmin(userId)) {
                if (arg.isNotBlank()) broadcast(chatId, arg)
                else { pending[userId] = Pending.Broadcast; tg.sendMessage(chatId, "📣 Send the message to broadcast (HTML allowed). /cancel to abort.") }
            }
            "ban", "unban" -> if (isAdmin(userId)) {
                val id = arg.toLongOrNull()
                if (id == null) tg.sendMessage(chatId, "Usage: /$cmd <user id>") else {
                    Store.user(id).banned = cmd == "ban"; Store.markDirty()
                    tg.sendMessage(chatId, "✅ User <code>$id</code> ${if (cmd == "ban") "banned" else "unbanned"}.")
                }
            }
            "cleancache" -> if (isAdmin(userId)) {
                val days = arg.toIntOrNull() ?: Config.cacheDays.coerceAtLeast(1)
                val (n, b) = Downloader.cleanCache(days)
                tg.sendMessage(chatId, "🧹 Deleted $n cached chapters (${Ui.size(b)}) older than $days days.")
            }
            "testproviders" -> if (isAdmin(userId)) testProviders(chatId, arg.ifBlank { "the" })
            else -> tg.sendMessage(chatId, "Unknown command. See /help")
        }
    }

    private suspend fun welcome(chatId: Long, user: UserData) {
        val name = h(user.name?.substringBefore(' ') ?: "reader")
        tg.sendMessage(
            chatId,
            """👋 <b>Hi $name!</b>

I'm <b>QuickNovel Bot</b> — I search <b>${Providers.all.size} web-novel sites</b> at once and turn any novel into a clean <b>EPUB / TXT / HTML</b> book for your e-reader.

<b>How it works</b>
1️⃣ Send me a novel name (e.g. <code>Lord of the Mysteries</code>)
2️⃣ Pick a result → see info, cover &amp; chapters
3️⃣ Tap <b>📥 Download</b> → get the book file right here
Or paste a link from a supported site 🔗

Current format: <b>${BookFormat.of(user.settings.format).label}</b> · change it in /settings""",
            keyboard(menuRows())
        )
    }

    private fun menuRows(): List<List<Btn>> = listOf(
        listOf(Btn("🧭 Browse popular", "b:0"), Btn("⚙️ Settings", "st:home")),
        listOf(Btn("⭐ Library", "ls:L:0"), Btn("🕘 History", "ls:H:0")),
        listOf(Btn("🌐 Supported sites", "src"), Btn("❓ Help", "help")),
    )

    /** Main menu: every screen has a 🏠 button that leads back here */
    private suspend fun mainMenu(chatId: Long, messageId: Long?) {
        val text = "🏠 <b>Main menu</b>\n\n🔍 Just type a novel name to search, or paste a link.\nOr pick an option:"
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(menuRows()))) tg.sendMessage(chatId, text, keyboard(menuRows()))
    }

    private suspend fun help(chatId: Long) {
        tg.sendMessage(
            chatId,
            """❓ <b>Help</b>

🔍 <b>Search</b> — just type a title, or <code>/search title</code>
🔗 <b>Links</b> — paste a novel URL from any supported site
🏠 /menu — main menu (every screen also has ⬅️ Back / 🏠 Menu buttons)
🧭 /browse — popular / latest novels per site
📖 <b>Read</b> — read chapters right inside Telegram
📥 <b>Download</b> — all chapters, first/last 50/100 or a custom range like <code>20-150</code>
⭐ /library — novels you saved · 🕘 /history
⚙️ /settings — default format, languages, enable/disable sites, author notes
📥 /queue · ✖️ /cancel

<i>Big novels are split into several volumes automatically so they fit Telegram's file limit. Chapters are cached, so re-downloads and updates are fast. When a site can't deliver some chapters, the bot fetches them from another site that has the same novel.</i>""",
            keyboard(listOf(listOf(Btn("🏠 Menu", "home"))))
        )
    }

    private suspend fun sources(chatId: Long) {
        val byLang = Providers.all.groupBy { it.lang }.toSortedMap(compareBy({ it != "en" }, { it }))
        val sb = StringBuilder("🌐 <b>${Providers.all.size} supported sites</b>\n")
        byLang.forEach { (lang, apis) ->
            sb.append("\n").append(Ui.flag(lang)).append(" <b>").append(lang.uppercase()).append("</b>\n")
            sb.append(apis.joinToString(" · ") { "<a href=\"${h(it.mainUrl)}\">${h(it.name)}</a>" }).append('\n')
        }
        sb.append("\n<i>Enable/disable sites in /settings</i>")
        tg.sendMessage(chatId, sb.toString(), keyboard(listOf(listOf(Btn("⚙️ Settings", "st:home"), Btn("🏠 Menu", "home")))))
    }

    // ------------------------------------------------------------------ search

    private fun searchApis(user: UserData): List<MainAPI> = Providers.all.filter { api ->
        api.name !in user.settings.disabledProviders &&
                (user.settings.languages.isEmpty() || api.lang in user.settings.languages) &&
                !Providers.isPaused(api) // sites that keep failing are skipped for a while
    }

    private suspend fun search(chatId: Long, userId: Long, query: String, replyTo: Long?) {
        val q = query.take(100)
        if (q.length < 2) { tg.sendMessage(chatId, "Please send at least 2 characters."); return }
        if (!searching.add(userId)) { tg.sendMessage(chatId, "⏳ Please wait, your previous search is still running…"); return }
        try {
            val user = Store.user(userId)
            val apis = searchApis(user)
            if (apis.isEmpty()) { tg.sendMessage(chatId, "All sites are disabled in /settings 🙃"); return }
            tg.sendChatAction(chatId, "typing")
            val msg = tg.sendMessage(chatId, "🔍 Searching <b>${h(q)}</b> on ${apis.size} sites…\n${Ui.bar(0, apis.size)}", replyTo = replyTo)
            var lastEdit = 0L
            val start = System.currentTimeMillis()
            val results = Providers.searchAll(q, apis) { done, total, found ->
                val now = System.currentTimeMillis()
                if (now - lastEdit > 1500 && done < total) {
                    lastEdit = now
                    tg.editMessage(chatId, msg, "🔍 Searching <b>${h(q)}</b>…\n${Ui.bar(done, total)} $done/$total sites\n📚 $found results so far")
                }
            }
            val ranked = Providers.rank(q, results)
            user.searches++; Store.stats.searches++; Store.markDirty()
            val ok = results.count { it.results.isNotEmpty() }
            val failed = results.count { it.error != null && it.error != "search not supported" }
            val summary = "${ranked.size} results from $ok sites in ${Ui.duration(System.currentTimeMillis() - start)}" +
                    if (failed > 0) " · $failed sites failed" else ""
            if (ranked.isEmpty()) {
                tg.editMessage(chatId, msg, "😕 Nothing found for <b>${h(q)}</b>.\n<i>$summary</i>\n\nTry a shorter or the original (English) title.",
                    keyboard(listOf(listOf(Btn("🧭 Browse instead", "b:0"), Btn("🏠 Menu", "home")))))
                return
            }
            val s = newSession("🔍 ${q}", ranked, summary)
            showSession(chatId, msg, s, 0)
        } finally {
            searching.remove(userId)
        }
    }

    private fun newSession(title: String, results: List<SearchResponse>, summary: String, browseApi: String? = null, browsePage: Int = 1): Session {
        val id = sessionCounter.incrementAndGet().toString(36)
        val s = Session(id, title, results, summary, browseApi, browsePage)
        synchronized(sessions) { sessions[id] = s }
        return s
    }

    private fun session(id: String): Session? = synchronized(sessions) { sessions[id] }

    private val perPage = 8

    private suspend fun showSession(chatId: Long, messageId: Long?, s: Session, page: Int) {
        val pages = (s.results.size + perPage - 1) / perPage
        val p = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val slice = s.results.drop(p * perPage).take(perPage)
        val sb = StringBuilder("<b>${h(Ui.cut(s.title, 60))}</b>\n<i>${h(s.summary)}</i>\n\n")
        slice.forEachIndexed { i, r -> sb.append(Ui.resultLine(p * perPage + i + 1, r)).append("\n") }
        val nums = slice.mapIndexed { i, _ -> Btn("${p * perPage + i + 1}", "o:${s.id}:${p * perPage + i}") }
        val rows = mutableListOf<List<Btn>>()
        rows += nums.take(4); rows += nums.drop(4)
        val nav = mutableListOf<Btn>()
        if (p > 0) nav += Btn("⬅️", "s:${s.id}:${p - 1}")
        if (pages > 1) nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "s:${s.id}:${p + 1}")
        rows += nav
        if (s.browseApi != null) {
            val api = Providers.byName(s.browseApi)
            val idx = Providers.all.indexOf(api)
            val more = mutableListOf<Btn>()
            if (s.browsePage > 1) more += Btn("⏮ Site page ${s.browsePage - 1}", "bp:$idx:${s.browsePage - 1}")
            more += Btn("Site page ${s.browsePage + 1} ⏭", "bp:$idx:${s.browsePage + 1}")
            rows += more
            rows += listOf(Btn("⬅️ Other sites", "b:0"), Btn("🏠 Menu", "home"))
        } else {
            rows += listOf(Btn("🏠 Menu", "home"))
        }
        val text = sb.toString()
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    // ------------------------------------------------------------------ browse

    private suspend fun browseMenu(chatId: Long, messageId: Long?, page: Int) {
        val apis = Providers.all.withIndex().filter { it.value.hasMainPage }
        val per = 16
        val pages = (apis.size + per - 1) / per
        val p = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val btns = apis.drop(p * per).take(per).map { (i, a) -> Btn("${Ui.flag(a.lang)} ${a.name}", "bp:$i:1") }
        val rows = btns.chunked(2).toMutableList()
        val nav = mutableListOf<Btn>()
        if (p > 0) nav += Btn("⬅️", "b:${p - 1}")
        nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "b:${p + 1}")
        rows += nav
        rows += listOf(Btn("🏠 Menu", "home"))
        val text = "🧭 <b>Browse</b>\nPick a site to see its popular / latest novels:"
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private suspend fun browseApi(chatId: Long, messageId: Long?, api: MainAPI, page: Int) {
        val mid = messageId ?: tg.sendMessage(chatId, "⏳ Loading <b>${h(api.name)}</b>…")
        if (messageId != null) tg.editMessage(chatId, mid, "⏳ Loading <b>${h(api.name)}</b> page $page…")
        val idx = Providers.all.indexOf(api)
        val res = try {
            Providers.mainPage(api, page)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.warn("browse ${api.name} failed: ${t.message?.take(200)}")
            tg.editMessage(chatId, mid, "⚠️ <b>${h(api.name)}</b> is not working right now.\n<i>${h(Providers.friendlyError(t))}</i>\n\nTry again later or pick another site.",
                keyboard(listOf(
                    listOf(Btn("🔄 Retry", "bp:$idx:$page"), Btn("⬅️ Other sites", "b:0")),
                    listOf(Btn("🏠 Menu", "home")),
                )))
            return
        }
        if (res.list.isEmpty()) {
            tg.editMessage(chatId, mid, "😕 ${h(api.name)} returned nothing for page $page.",
                keyboard(listOf(listOf(Btn("⬅️ Other sites", "b:0"), Btn("🏠 Menu", "home")))))
            return
        }
        val label = listOfNotNull(api.mainCategories.firstOrNull()?.first, api.orderBys.firstOrNull()?.first).joinToString(" · ")
        val s = newSession("🧭 ${api.name} — page $page", res.list.distinctBy { it.url }, label.ifBlank { "${res.list.size} novels" }, api.name, page)
        showSession(chatId, mid, s, 0)
    }

    // ------------------------------------------------------------------ novel

    private suspend fun openUrl(chatId: Long, url: String) {
        val api = Providers.forUrl(url)
        if (api == null) {
            tg.sendMessage(chatId, "🤔 This site is not supported. See /sources for the list.")
            return
        }
        val ref = Store.ref(api.name, url)
        openNovel(chatId, ref, Store.resolve(ref)!!)
    }

    private suspend fun loadRef(ref: NovelRef, allowCache: Boolean = true): Pair<MainAPI, LoadResponse> {
        val api = Providers.byName(ref.api) ?: throw IllegalStateException("Provider ${ref.api} no longer exists")
        return api to Providers.load(api, ref.url, allowCache)
    }

    private fun novelKeyboard(ref: String, r: LoadResponse, user: UserData, back: Btn? = null): Map<String, Any> {
        val rows = mutableListOf<List<Btn>>()
        if (r is StreamResponse && r.data.isNotEmpty()) {
            rows += listOf(Btn("📥 Download", "d:$ref"), Btn("📖 Read", "r:$ref:0:0"))
            rows += listOf(Btn("📑 Chapters", "cl:$ref:0"), Btn("🆕 Latest", "r:$ref:${r.data.size - 1}:0"))
        } else if (r is EpubResponse) {
            rows += listOf(Btn("📥 Download book", "ep:$ref"))
        }
        rows += listOf(
            Btn(if (Store.inLibrary(user, ref)) "💔 Unsave" else "⭐ Save", "lib:$ref"),
            Btn("🔄 Refresh", "rf:$ref"),
        )
        if (r.url.startsWith("http")) rows += listOf(Btn("🌐 Open on ${Ui.cut(r.apiName, 24)}", url = r.url))
        rows += listOfNotNull(back, Btn("🏠 Menu", "home"))
        return keyboard(rows)
    }

    private suspend fun openNovel(chatId: Long, ref: String, nref: NovelRef, userId: Long? = null, allowCache: Boolean = true, back: Btn? = null) {
        tg.sendChatAction(chatId, "typing")
        val wait = tg.sendMessage(chatId, "⏳ Loading <b>${h(nref.title ?: nref.api)}</b>…")
        val (api, r) = try {
            loadRef(nref, allowCache)
        } catch (t: Throwable) {
            Logger.warn("load ${nref.url} failed: ${t.message}")
            tg.editMessage(chatId, wait, "⚠️ Could not load this novel from <b>${h(nref.api)}</b>.\n<i>${h(Providers.friendlyError(t))}</i>\n\nTry again, or search the title — the same novel is often on other sites too.",
                keyboard(listOf(
                    listOf(Btn("🔄 Try again", "rf:$ref"), Btn("🔍 Find on other sites", "alt:$ref")),
                    listOfNotNull(back, Btn("🏠 Menu", "home")),
                )))
            return
        }
        val realRef = Store.ref(api.name, nref.url, r.name)
        val user = Store.user(userId ?: chatId)
        Store.addHistory(user, realRef, r.name, api.name)
        val markup = novelKeyboard(realRef, r, user, back)

        if (user.settings.sendCover) {
            val cover = Downloader.cover(api, r.posterUrl, r.posterHeaders)?.let { BookBuilder.toJpeg(it, 1280, 4_000_000) }
            if (cover != null) {
                try {
                    tg.sendPhoto(chatId, cover, Ui.novelCard(r, api.lang, 1024), markup)
                    tg.deleteMessage(chatId, wait)
                    return
                } catch (t: Throwable) {
                    Logger.debug("sendPhoto failed", t)
                }
            }
        }
        if (!tg.editMessage(chatId, wait, Ui.novelCard(r, api.lang, 4000), markup)) tg.sendMessage(chatId, Ui.novelCard(r, api.lang, 4000), markup)
    }

    private suspend fun chapterList(chatId: Long, messageId: Long?, ref: String, page: Int) {
        val nref = Store.resolve(ref) ?: return expired(chatId)
        val (_, r) = loadRef(nref)
        val data = (r as? StreamResponse)?.data ?: return
        val per = 10
        val pages = (data.size + per - 1) / per
        val p = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val rows = data.withIndex().drop(p * per).take(per).map { (i, c) ->
            listOf(Btn(Ui.cut("${i + 1}. ${c.name}", 60), "r:$ref:$i:0"))
        }.toMutableList()
        val nav = mutableListOf<Btn>()
        if (p >= 10) nav += Btn("⏪", "cl:$ref:${p - 10}")
        if (p > 0) nav += Btn("⬅️", "cl:$ref:${p - 1}")
        nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "cl:$ref:${p + 1}")
        if (p + 10 < pages) nav += Btn("⏩", "cl:$ref:${p + 10}")
        rows += nav
        rows += listOf(Btn("📥 Download", "d:$ref"))
        rows += listOf(Btn("⬅️ Back to novel", "n:$ref"), Btn("🏠 Menu", "home"))
        val text = "📑 <b>${h(r.name)}</b>\n${data.size} chapters · page ${p + 1}/$pages"
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private fun paginate(text: String, max: Int = 3300): List<String> {
        val pages = mutableListOf<String>()
        val cur = StringBuilder()
        for (para in text.split("\n\n")) {
            if (cur.length + para.length + 2 > max && cur.isNotEmpty()) { pages += cur.toString().trim(); cur.clear() }
            if (para.length > max) {
                para.chunked(max).forEach { chunk -> if (cur.isNotEmpty()) { pages += cur.toString().trim(); cur.clear() }; cur.append(chunk) }
            } else cur.append(para).append("\n\n")
        }
        if (cur.isNotBlank()) pages += cur.toString().trim()
        return pages.ifEmpty { listOf("(empty chapter)") }
    }

    private suspend fun reader(chatId: Long, messageId: Long?, ref: String, chapter: Int, page: Int, newMessage: Boolean) {
        val nref = Store.resolve(ref) ?: return expired(chatId)
        val (api, r) = loadRef(nref)
        val data = (r as? StreamResponse)?.data ?: return
        if (data.isEmpty()) return
        val ch = chapter.coerceIn(0, data.size - 1)
        val c = data[ch]
        var mid = if (newMessage) null else messageId
        val key = "${api.name}|${c.url}"
        val pages = synchronized(readerCache) { readerCache[key] } ?: run {
            if (mid != null) tg.editMessage(chatId, mid, "⏳ Loading <b>${h(c.name)}</b>…") else mid = tg.sendMessage(chatId, "⏳ Loading <b>${h(c.name)}</b>…")
            val html = Downloader.chapterHtml(api, c)
            if (html == null) {
                tg.editMessage(chatId, mid!!, "⚠️ Could not load <b>${h(c.name)}</b>.", keyboard(listOf(
                    listOf(Btn("🔄 Retry", "r:$ref:$ch:0:1"), Btn("🌐 Open chapter", url = c.url)),
                    listOf(Btn("📑 Chapters", "cl:$ref:${ch / 10}"), Btn("⬅️ Back to novel", "n:$ref")))))
                return
            }
            val clean = ChapterCleaner.clean(c.name, html, Store.user(chatId).settings.stripAuthorNotes)
            paginate(ChapterCleaner.toText(clean)).also { synchronized(readerCache) { readerCache[key] = it } }
        }
        val p = page.coerceIn(0, pages.size - 1)
        val header = "📖 <b>${h(Ui.cut(c.name, 120))}</b>\n<i>${h(Ui.cut(r.name, 80))} · ${ch + 1}/${data.size}" +
                (if (pages.size > 1) " · part ${p + 1}/${pages.size}" else "") + "</i>\n\n"
        val text = header + h(pages[p])
        val nav = mutableListOf<Btn>()
        when {
            p > 0 -> nav += Btn("◀️ Part ${p}", "r:$ref:$ch:${p - 1}:1")
            ch > 0 -> nav += Btn("⏮ Prev ch.", "r:$ref:${ch - 1}:0:1")
        }
        when {
            p < pages.size - 1 -> nav += Btn("Part ${p + 2} ▶️", "r:$ref:$ch:${p + 1}:1")
            ch < data.size - 1 -> nav += Btn("Next ch. ⏭", "r:$ref:${ch + 1}:0:1")
        }
        val rows = listOf(nav, listOf(Btn("📑 Chapters", "cl:$ref:${ch / 10}"), Btn("📥 Download", "d:$ref")),
            listOf(Btn("⬅️ Back to novel", "n:$ref"), Btn("🏠 Menu", "home")))
        if (mid == null || !tg.editMessage(chatId, mid!!, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))

        // prefetch next chapter quietly
        if (p == pages.size - 1 && ch < data.size - 1 && !Downloader.isCached(api, data[ch + 1].url)) {
            scope.launch { runCatching { Downloader.chapterHtml(api, data[ch + 1], maxTries = 1) } }
        }
    }

    // ------------------------------------------------------------------ download

    private suspend fun downloadMenu(chatId: Long, messageId: Long?, ref: String, userId: Long, format: BookFormat? = null) {
        val nref = Store.resolve(ref) ?: return expired(chatId)
        val (_, r) = loadRef(nref)
        val total = Ui.chapterCount(r)
        val user = Store.user(userId)
        val fmt = format ?: BookFormat.of(user.settings.format)
        val f = fmt.name.take(1) // E/T/H
        val rows = mutableListOf<List<Btn>>()
        rows += BookFormat.entries.map { Btn(if (it == fmt) "• ${it.label} •" else it.label, "df:$ref:${it.name.take(1)}") }
        rows += listOf(Btn("📚 All $total chapters", "dg:$ref:$f:1:$total"))
        if (total > 60) {
            rows += listOf(Btn("First 50", "dg:$ref:$f:1:50"), Btn("Last 50", "dg:$ref:$f:${total - 49}:$total"))
        }
        if (total > 150) {
            rows += listOf(Btn("First 100", "dg:$ref:$f:1:100"), Btn("Last 100", "dg:$ref:$f:${total - 99}:$total"))
        }
        rows += listOf(Btn("✏️ Custom range…", "dc:$ref:$f"))
        rows += listOf(Btn("⬅️ Back to novel", "n:$ref"), Btn("📑 Chapters", "cl:$ref:0"))
        val limitNote = if (!isAdmin(userId) && Config.maxChapters in 1 until total) "\n⚠️ Max ${Config.maxChapters} chapters per download." else ""
        val text = "📥 <b>Download</b> — ${h(r.name)}\n$total chapters · format: <b>${fmt.label}</b>$limitNote\n\nChoose what to download:"
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private fun fmtOf(c: String) = BookFormat.entries.firstOrNull { it.name.startsWith(c) } ?: BookFormat.EPUB

    private suspend fun startDownloadFromText(chatId: Long, userId: Long, p: Pending.Range, text: String) {
        val nref = Store.resolve(p.ref) ?: return expired(chatId)
        val (_, r) = loadRef(nref)
        val total = Ui.chapterCount(r)
        val t = text.replace(" ", "").lowercase()
        val m = Regex("^(\\d*)[-–—:](\\d*)$").find(t)
        val range: IntRange? = when {
            m != null -> {
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: total
                a..b
            }
            t.toIntOrNull() != null -> t.toInt()..t.toInt()
            t.startsWith("last") -> t.removePrefix("last").toIntOrNull()?.let { (total - it + 1)..total }
            t.startsWith("first") -> t.removePrefix("first").toIntOrNull()?.let { 1..it }
            else -> null
        }
        if (range == null || range.first > range.last) {
            pending[userId] = p
            tg.sendMessage(chatId, "❌ I didn't understand that. Send a range like <code>1-100</code>, <code>250-</code>, <code>last 30</code> or /cancel.")
            return
        }
        startDownload(chatId, userId, p.ref, p.format, range.first.coerceIn(1, total), range.last.coerceIn(1, total))
    }

    private suspend fun startDownload(chatId: Long, userId: Long, ref: String, format: BookFormat, from: Int, to: Int) {
        val nref = Store.resolve(ref) ?: return expired(chatId)
        if (jobs.values.count { it.userId == userId } >= (if (isAdmin(userId)) 5 else 1)) {
            tg.sendMessage(chatId, "⏳ You already have a download running. Wait for it or /cancel it.")
            return
        }
        var a = from; var b = to
        if (!isAdmin(userId) && Config.maxChapters > 0 && b - a + 1 > Config.maxChapters) {
            b = a + Config.maxChapters - 1
            tg.sendMessage(chatId, "ℹ️ Limited to ${Config.maxChapters} chapters per download: ${a}-${b}. Download the rest afterwards with a custom range.")
        }
        val job = DlJob(jobIds.getAndIncrement(), userId, chatId, nref.title ?: "novel")
        jobs[job.id] = job
        val queued = jobs.values.count { !it.started && it.id != job.id }
        job.messageId = tg.sendMessage(
            chatId,
            "📥 <b>${h(job.title)}</b>\nChapters $a–$b · ${format.label}\n" +
                    (if (queued > 0 || downloadSlots.availablePermits == 0) "🕒 Waiting in queue…" else "⏳ Starting…"),
            keyboard(listOf(listOf(Btn("✖️ Cancel", "x:${job.id}"))))
        )
        job.job = scope.launch {
            try {
                downloadSlots.withPermit {
                    job.started = true
                    runDownload(job, nref, format, a, b)
                }
            } catch (e: CancellationException) {
                tg.editMessage(chatId, job.messageId, "✖️ Download of <b>${h(job.title)}</b> cancelled.")
            } catch (t: Throwable) {
                Logger.error("Download ${nref.url} failed", t)
                val reason = if (t is IllegalStateException) t.message.orEmpty() else Providers.friendlyError(t)
                tg.editMessage(chatId, job.messageId, "⚠️ Download failed: <i>${h(Ui.cut(reason, 300))}</i>",
                    keyboard(listOf(
                        listOf(Btn("🔄 Try again", "dg:$ref:${format.name.take(1)}:$a:$b"), Btn("🔍 Other sites", "alt:$ref")),
                        listOf(Btn("⬅️ Back to novel", "n:$ref"), Btn("🏠 Menu", "home")),
                    )))
            } finally {
                jobs.remove(job.id)
            }
        }
    }

    private fun fileName(title: String, a: Int, b: Int, total: Int, ext: String, part: Int? = null): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "novel" }
        val range = if (a == 1 && b == total) "" else " (Ch $a-$b)"
        val p = part?.let { " - Vol ${"%02d".format(it)}" } ?: ""
        return "$clean$range$p.$ext"
    }

    private suspend fun runDownload(job: DlJob, nref: NovelRef, format: BookFormat, from: Int, to: Int) {
        val chatId = job.chatId
        val cancelKb = keyboard(listOf(listOf(Btn("✖️ Cancel", "x:${job.id}"))))
        tg.editMessage(chatId, job.messageId, "📥 <b>${h(job.title)}</b>\n⏳ Loading novel info…", cancelKb)
        val (api, r) = loadRef(nref, allowCache = false)
        val data = (r as? StreamResponse)?.data ?: throw IllegalStateException("This novel has no chapters")
        val a = from.coerceIn(1, data.size); val b = to.coerceIn(a, data.size)
        val selected = (a - 1 until b).map { it to data[it] }
        val user = Store.user(job.userId)
        val started = System.currentTimeMillis()

        val (chapters, failed) = Downloader.download(api, selected, user.settings.stripAuthorNotes, r.name, data) { pr ->
            val elapsed = System.currentTimeMillis() - started
            val fetched = pr.done - pr.cached.coerceAtMost(pr.done)
            val eta = if (fetched > 3 && pr.done < pr.total) " · ETA ${Ui.duration(elapsed * (pr.total - pr.done) / pr.done.coerceAtLeast(1))}" else ""
            tg.editMessage(
                chatId, job.messageId,
                "📥 <b>${h(job.title)}</b>\n${Ui.bar(pr.done, pr.total)} ${pr.done * 100 / pr.total.coerceAtLeast(1)}%\n" +
                        "Chapters ${pr.done}/${pr.total}" + (if (pr.failed > 0) " · ⚠️ ${pr.failed} failed" else "") + eta +
                        (if (pr.cached > 0) "\n<i>${pr.cached} from cache</i>" else "") +
                        (pr.stage?.let { "\n🔁 <i>${h(it)}…</i>" } ?: "") +
                        (if (api.hasRateLimit) "\n<i>${h(api.name)} is rate limited, please be patient</i>" else ""),
                cancelKb
            )
        }
        if (failed == selected.size) throw IllegalStateException("No chapter could be downloaded from ${api.name} (and no other site with this novel worked). Try the same novel from another site.")

        tg.editMessage(chatId, job.messageId, "📦 <b>${h(job.title)}</b>\nBuilding ${format.label}…")
        tg.sendChatAction(chatId, "upload_document")
        val coverRaw = Downloader.cover(api, r.posterUrl, r.posterHeaders)
        val meta = BookMeta(r.name, r.author?.trim()?.ifBlank { null }, r.synopsis?.trim(), api.name, r.url, r.tags.orEmpty().filter { it.isNotBlank() }, api.lang, coverRaw)
        val thumb = coverRaw?.let { BookBuilder.toJpeg(it, 320, 190_000) }

        // build, split into volumes when larger than the upload limit
        var parts = 1
        var files: List<Pair<String, ByteArray>>
        while (true) {
            val chunks = if (parts == 1) listOf(chapters) else chapters.chunked((chapters.size + parts - 1) / parts)
            files = chunks.mapIndexed { i, chs ->
                val f = chs.first().index + 1; val l = chs.last().index + 1
                val m = if (parts == 1) meta else meta.copy(title = "${meta.title} (Vol ${i + 1}: Ch $f-$l)")
                fileName(r.name, f, l, if (parts == 1) data.size else -1, format.ext, if (parts == 1) null else i + 1) to BookBuilder.build(format, m, chs)
            }
            val biggest = files.maxOf { it.second.size }
            if (biggest <= Config.maxUploadBytes || parts >= chapters.size) break
            parts = maxOf(parts + 1, (parts * biggest.toDouble() / Config.maxUploadBytes * 1.1).toInt() + 1)
        }

        files.forEachIndexed { i, (name, bytes) ->
            tg.editMessage(chatId, job.messageId, "📤 <b>${h(job.title)}</b>\nUploading" + (if (files.size > 1) " volume ${i + 1}/${files.size}" else "") + " (${Ui.size(bytes.size.toLong())})…")
            tg.sendChatAction(chatId, "upload_document")
            val caption = "📖 <b>${h(Ui.cut(r.name, 200))}</b>\n" +
                    (r.author?.takeIf { it.isNotBlank() }?.let { "✍️ ${h(Ui.cut(it.trim(), 80))}\n" } ?: "") +
                    "📚 Chapters $a–$b" + (if (files.size > 1) " · Vol ${i + 1}/${files.size}" else "") +
                    " · ${h(api.name)}\n🤖 @$botUsername"
            tg.sendDocument(chatId, name, bytes, caption, format.mime, thumb)
            Store.stats.bytesSent += bytes.size
        }
        val took = Ui.duration(System.currentTimeMillis() - started)
        user.downloads++; Store.stats.downloads++; Store.stats.chapters += selected.size; Store.markDirty()
        val doneRows = mutableListOf<List<Btn>>()
        if (failed > 0) doneRows += listOf(Btn("🔄 Retry failed", "dg:${Store.ref(api.name, nref.url)}:${format.name.take(1)}:$a:$b"), Btn("🔍 Other sites", "alt:${Store.ref(api.name, nref.url)}"))
        doneRows += listOf(Btn("⬅️ Back to novel", "n:${Store.ref(api.name, nref.url)}"), Btn("🏠 Menu", "home"))
        tg.editMessage(
            chatId, job.messageId,
            "✅ <b>${h(job.title)}</b>\n${selected.size - failed}/${selected.size} chapters · ${files.size} file(s) · $took" +
                    (if (failed > 0) "\n⚠️ $failed chapters failed (placeholders inserted). Retry later — successful chapters are cached, so only the missing ones are fetched." else ""),
            keyboard(doneRows)
        )
    }

    private suspend fun downloadEpubResponse(chatId: Long, userId: Long, ref: String) {
        val nref = Store.resolve(ref) ?: return expired(chatId)
        val (api, r) = loadRef(nref)
        val e = r as? EpubResponse ?: return
        val msg = tg.sendMessage(chatId, "🔗 Resolving download links for <b>${h(r.name)}</b>…")
        val links = ExtractorApi.extract(e.downloadExtractLinks + e.downloadLinks).sortedByDescending { it.kbPerSec }
        for ((i, link) in links.withIndex()) {
            tg.editMessage(chatId, msg, "⬇️ Downloading <b>${h(r.name)}</b>… (mirror ${i + 1}/${links.size})")
            try {
                val res = link.get()
                val bytes = res.body.bytes()
                if (!res.isSuccessful || bytes.size < 10_000) continue
                if (bytes.size > Config.maxUploadBytes) {
                    tg.editMessage(chatId, msg, "⚠️ The book is ${Ui.size(bytes.size.toLong())}, larger than Telegram's limit.", keyboard(listOf(listOf(Btn("⬇️ Direct link", url = link.url)))))
                    return
                }
                val ext = when {
                    bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> "pdf"
                    else -> "epub"
                }
                tg.sendDocument(chatId, fileName(r.name, 1, 1, 1, ext), bytes, "📖 <b>${h(r.name)}</b>\n${h(api.name)} · 🤖 @$botUsername")
                tg.deleteMessage(chatId, msg)
                Store.user(userId).downloads++; Store.stats.downloads++; Store.markDirty()
                return
            } catch (t: Throwable) {
                Logger.debug("mirror ${link.url} failed", t)
            }
        }
        tg.editMessage(chatId, msg, "😕 No working download mirror found for <b>${h(r.name)}</b>.")
    }

    private suspend fun queue(chatId: Long, userId: Long) {
        val all = jobs.values.sortedBy { it.id }
        val mine = all.filter { it.userId == userId }
        if (mine.isEmpty()) { tg.sendMessage(chatId, "📭 No downloads running. Global queue: ${all.size}.", keyboard(listOf(listOf(Btn("🏠 Menu", "home"))))); return }
        val text = "📥 <b>Your downloads</b>\n" + mine.joinToString("\n") { j ->
            "• ${h(j.title)} — " + (if (j.started) "downloading" else "waiting (#${all.filter { !it.started }.indexOf(j) + 1})")
        }
        tg.sendMessage(chatId, text, keyboard(mine.map { listOf(Btn("✖️ Cancel ${Ui.cut(it.title, 30)}", "x:${it.id}")) }))
    }

    // ------------------------------------------------------------------ library / history

    private suspend fun listSaved(chatId: Long, messageId: Long?, user: UserData, library: Boolean, page: Int = 0) {
        val items = if (library) user.library else user.history
        val title = if (library) "⭐ <b>Your library</b>" else "🕘 <b>Recently opened</b>"
        if (items.isEmpty()) {
            val t = if (library) "$title\n\nEmpty — tap ⭐ Save on a novel to keep it here." else "$title\n\nNothing yet. Search for a novel!"
            val kb = keyboard(listOf(listOf(Btn("🏠 Menu", "home"))))
            if (messageId == null || !tg.editMessage(chatId, messageId, t, kb)) tg.sendMessage(chatId, t, kb)
            return
        }
        val per = 10
        val pages = (items.size + per - 1) / per
        val p = page.coerceIn(0, pages - 1)
        val k = if (library) "L" else "H"
        val rows = items.drop(p * per).take(per).map { listOf(Btn(Ui.cut("${it.title} · ${it.api}", 60), "n:${it.ref}:$k$p")) }.toMutableList()
        val nav = mutableListOf<Btn>()
        if (p > 0) nav += Btn("⬅️", "ls:$k:${p - 1}")
        if (pages > 1) nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "ls:$k:${p + 1}")
        rows += nav
        rows += listOf(Btn(if (library) "🕘 History" else "⭐ Library", if (library) "ls:H:0" else "ls:L:0"), Btn("🏠 Menu", "home"))
        val t = "$title (${items.size})"
        if (messageId == null || !tg.editMessage(chatId, messageId, t, keyboard(rows))) tg.sendMessage(chatId, t, keyboard(rows))
    }

    // ------------------------------------------------------------------ settings

    private suspend fun settings(chatId: Long, messageId: Long?, user: UserData) {
        val s = user.settings
        val langs = if (s.languages.isEmpty()) "all" else s.languages.sorted().joinToString(", ") { "${Ui.flag(it)} ${it.uppercase()}" }
        val enabled = Providers.all.count { it.name !in s.disabledProviders }
        val text = """⚙️ <b>Settings</b>

📘 Default format: <b>${BookFormat.of(s.format).label}</b>
🌍 Search languages: <b>$langs</b>
🌐 Sites enabled: <b>$enabled/${Providers.all.size}</b>
📝 Remove author notes: <b>${if (s.stripAuthorNotes) "yes" else "no"}</b>
🖼 Show covers: <b>${if (s.sendCover) "yes" else "no"}</b>"""
        val rows = listOf(
            BookFormat.entries.map { Btn((if (BookFormat.of(s.format) == it) "✅ " else "") + it.label, "st:fmt:${it.name}") },
            listOf(Btn("🌍 Languages", "st:langs"), Btn("🌐 Sites", "st:prov:0")),
            listOf(Btn("📝 Author notes: ${if (s.stripAuthorNotes) "remove" else "keep"}", "st:notes")),
            listOf(Btn("🖼 Covers: ${if (s.sendCover) "on" else "off"}", "st:cover")),
            listOf(Btn("🏠 Menu", "home")),
        )
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private suspend fun languageSettings(chatId: Long, messageId: Long, user: UserData) {
        val s = user.settings
        val btns = Providers.languages.map { l ->
            val n = Providers.all.count { it.lang == l }
            Btn((if (l in s.languages) "✅ " else "") + "${Ui.flag(l)} ${l.uppercase()} ($n)", "st:lang:$l")
        }
        val rows = btns.chunked(3).toMutableList()
        rows += listOf(Btn(if (s.languages.isEmpty()) "✅ All languages" else "All languages", "st:lang:*"), Btn("⬅️ Back", "st:home"))
        tg.editMessage(chatId, messageId, "🌍 <b>Search languages</b>\nOnly sites in the selected languages are searched. Select none for all.", keyboard(rows))
    }

    private suspend fun providerSettings(chatId: Long, messageId: Long, user: UserData, page: Int) {
        val per = 16
        val all = Providers.all
        val pages = (all.size + per - 1) / per
        val p = page.coerceIn(0, pages - 1)
        val btns = all.withIndex().drop(p * per).take(per).map { (i, a) ->
            Btn((if (a.name in user.settings.disabledProviders) "❌ " else "✅ ") + Ui.cut(a.name, 22), "st:pt:$i:$p")
        }
        val rows = btns.chunked(2).toMutableList()
        val nav = mutableListOf<Btn>()
        if (p > 0) nav += Btn("⬅️", "st:prov:${p - 1}")
        nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "st:prov:${p + 1}")
        rows += nav
        rows += listOf(Btn("✅ Enable all", "st:pall:1:$p"), Btn("❌ Disable all", "st:pall:0:$p"))
        rows += listOf(Btn("⬅️ Back", "st:home"))
        tg.editMessage(chatId, messageId, "🌐 <b>Sites used for search</b>\nTap to enable / disable.", keyboard(rows))
    }

    // ------------------------------------------------------------------ admin

    private suspend fun stats(chatId: Long) {
        val st = Store.stats
        val day = System.currentTimeMillis() - 24 * 3600 * 1000L
        val (n, b) = Downloader.cacheSize()
        val rt = Runtime.getRuntime()
        tg.sendMessage(
            chatId, """📊 <b>Stats</b>
👥 Users: ${Store.users.size} (active 24h: ${Store.users.values.count { it.lastSeen > day }})
🔍 Searches: ${st.searches}
📥 Downloads: ${st.downloads} · chapters: ${st.chapters}
📤 Sent: ${Ui.size(st.bytesSent)}
🗂 Chapter cache: $n files, ${Ui.size(b)}
⚙️ Jobs: ${jobs.size} (${jobs.values.count { it.started }} running)
💾 Memory: ${Ui.size(rt.totalMemory() - rt.freeMemory())} / ${Ui.size(rt.maxMemory())}"""
        )
    }

    private suspend fun broadcast(chatId: Long, text: String) {
        val targets = Store.users.values.filter { !it.banned }.map { it.id }
        val msg = tg.sendMessage(chatId, "📣 Broadcasting to ${targets.size} users…")
        var ok = 0; var fail = 0
        for (id in targets) {
            try { tg.sendMessage(id, text); ok++ } catch (_: Throwable) { fail++ }
            delay(50)
        }
        tg.editMessage(chatId, msg, "📣 Done: $ok delivered, $fail failed.")
    }

    private suspend fun testProviders(chatId: Long, query: String) {
        val msg = tg.sendMessage(chatId, "🧪 Testing ${Providers.all.size} providers with “${h(query)}”…")
        val res = Providers.searchAll(query, Providers.all)
        val sb = StringBuilder("🧪 <b>Provider test</b> “${h(query)}”\n\n")
        res.sortedWith(compareBy({ it.results.isEmpty() }, { it.api.name })).forEach { r ->
            val icon = when { r.results.isNotEmpty() -> "✅"; r.error == null -> "⚪"; else -> "❌" }
            sb.append("$icon ${h(r.api.name)} — ${r.results.size} (${r.millis / 100 / 10.0}s)")
            r.error?.let { sb.append(" <i>${h(Ui.cut(it, 50))}</i>") }
            sb.append('\n')
        }
        val text = sb.toString()
        if (text.length < 4000) tg.editMessage(chatId, msg, text)
        else { tg.deleteMessage(chatId, msg); text.chunked(3900).forEach { tg.sendMessage(chatId, it.replace(Regex("<[^>]*$"), "")) } }
    }

    private suspend fun expired(chatId: Long) {
        tg.sendMessage(chatId, "⌛ This button expired. Please search again.", keyboard(listOf(listOf(Btn("🏠 Menu", "home")))))
    }

    // ------------------------------------------------------------------ callbacks

    private suspend fun onCallback(cb: JsonNode) {
        val id = cb.path("id").asText()
        val data = cb.path("data").asText("")
        val from = cb.path("from")
        val userId = from.path("id").asLong()
        val msg = cb.path("message")
        val chatId = msg.path("chat").path("id").asLong()
        val messageId = msg.path("message_id").asLong()
        val isPhoto = msg.has("photo")
        val textMid: Long? = if (isPhoto) null else messageId // photo messages can't become text messages

        if (!allowed(userId)) { tg.answerCallback(id, "🔒 Not allowed", true); return }
        val user = Store.user(userId, displayName(from), from.path("username").asText(null))
        val p = data.split(':')

        when (p[0]) {
            "noop" -> tg.answerCallback(id)
            "home" -> { tg.answerCallback(id); mainMenu(chatId, textMid) }
            "alt" -> {
                val ref = Store.resolve(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired"); return }
                tg.answerCallback(id, "🔍 Searching other sites…")
                val title = ref.title
                if (title.isNullOrBlank()) tg.sendMessage(chatId, "Send me the novel name to search all sites 🔍")
                else search(chatId, userId, title, null)
            }
            "help" -> { tg.answerCallback(id); help(chatId) }
            "src" -> { tg.answerCallback(id); sources(chatId) }
            "s" -> {
                val s = session(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired, search again"); return }
                tg.answerCallback(id); showSession(chatId, messageId, s, p[2].toInt())
            }
            "o" -> {
                val s = session(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired, search again"); return }
                val r = s.results.getOrNull(p[2].toInt()) ?: return tg.answerCallback(id)
                tg.answerCallback(id, "📖 ${Ui.cut(r.name, 40)}")
                val ref = Store.ref(r.apiName, r.url, r.name)
                val backBtn = Btn("⬅️ Back to results", "s:${s.id}:${p[2].toInt() / perPage}")
                openNovel(chatId, ref, Store.resolve(ref)!!, userId, back = backBtn)
            }
            "n" -> {
                val ref = Store.resolve(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired"); return }
                // "n:<ref>:L0" / "n:<ref>:H2" = opened from library/history page => back goes there
                val backBtn = p.getOrNull(2)?.takeIf { it.length >= 2 && it[0] in "LH" }?.let {
                    Btn(if (it[0] == 'L') "⬅️ Back to library" else "⬅️ Back to history", "ls:${it[0]}:${it.drop(1).toIntOrNull() ?: 0}")
                }
                tg.answerCallback(id); openNovel(chatId, p[1], ref, userId, back = backBtn)
            }
            "rf" -> {
                val ref = Store.resolve(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired"); return }
                tg.answerCallback(id, "🔄 Refreshing…"); openNovel(chatId, p[1], ref, userId, allowCache = false)
            }
            "cl" -> { tg.answerCallback(id); chapterList(chatId, textMid, p[1], p[2].toInt()) }
            "r" -> {
                tg.answerCallback(id)
                // buttons inside the reader carry ":1" => edit in place; from cards/lists => new message
                val fromReader = !isPhoto && p.getOrNull(4) == "1"
                reader(chatId, messageId, p[1], p[2].toInt(), p[3].toInt(), newMessage = !fromReader)
            }
            "d" -> { tg.answerCallback(id); downloadMenu(chatId, null, p[1], userId) }
            "df" -> { tg.answerCallback(id); downloadMenu(chatId, textMid, p[1], userId, fmtOf(p[2])) }
            "dg" -> {
                tg.answerCallback(id, "📥 Added to queue")
                if (!isPhoto && msg.path("text").asText("").startsWith("📥 Download")) tg.editMarkup(chatId, messageId, null)
                startDownload(chatId, userId, p[1], fmtOf(p[2]), p[3].toInt(), p[4].toInt())
            }
            "dc" -> {
                tg.answerCallback(id)
                pending[userId] = Pending.Range(p[1], fmtOf(p[2]))
                tg.sendMessage(chatId, "✏️ Send the chapter range, e.g. <code>1-100</code>, <code>250-</code> (to the end), <code>last 30</code>.\n/cancel to abort.",
                    keyboard(listOf(listOf(Btn("⬅️ Back", "d:${p[1]}")))))
            }
            "ep" -> { tg.answerCallback(id); downloadEpubResponse(chatId, userId, p[1]) }
            "x" -> {
                val j = jobs[p[1].toIntOrNull() ?: -1]
                if (j == null || (j.userId != userId && !isAdmin(userId))) tg.answerCallback(id, "Already finished")
                else { j.job?.cancel(); tg.answerCallback(id, "✖️ Cancelled") }
            }
            "lib" -> {
                val ref = Store.resolve(p[1]) ?: run { tg.answerCallback(id, "⌛ Expired"); return }
                val added = Store.toggleLibrary(user, p[1], ref.title ?: "Novel", ref.api)
                tg.answerCallback(id, if (added) "⭐ Saved to your library" else "💔 Removed from library")
                runCatching {
                    val (_, r) = loadRef(ref)
                    tg.editMarkup(chatId, messageId, novelKeyboard(p[1], r, user))
                }
            }
            "ls" -> { tg.answerCallback(id); listSaved(chatId, textMid, user, p[1] == "L", p[2].toInt()) }
            "b" -> { tg.answerCallback(id); browseMenu(chatId, textMid, p[1].toInt()) }
            "bp" -> {
                val api = Providers.all.getOrNull(p[1].toInt()) ?: return tg.answerCallback(id)
                tg.answerCallback(id); browseApi(chatId, textMid, api, p[2].toInt().coerceAtLeast(1))
            }
            "st" -> {
                val s = user.settings
                when (p[1]) {
                    "home" -> { tg.answerCallback(id); settings(chatId, textMid, user) }
                    "fmt" -> { s.format = BookFormat.of(p[2]).name; Store.markDirty(); tg.answerCallback(id, "Format: ${BookFormat.of(p[2]).label}"); settings(chatId, textMid, user) }
                    "notes" -> { s.stripAuthorNotes = !s.stripAuthorNotes; Store.markDirty(); tg.answerCallback(id); settings(chatId, textMid, user) }
                    "cover" -> { s.sendCover = !s.sendCover; Store.markDirty(); tg.answerCallback(id); settings(chatId, textMid, user) }
                    "langs" -> { tg.answerCallback(id); languageSettings(chatId, messageId, user) }
                    "lang" -> {
                        if (p[2] == "*") s.languages.clear() else if (!s.languages.remove(p[2])) s.languages.add(p[2])
                        Store.markDirty(); tg.answerCallback(id); languageSettings(chatId, messageId, user)
                    }
                    "prov" -> { tg.answerCallback(id); providerSettings(chatId, messageId, user, p[2].toInt()) }
                    "pt" -> {
                        val api = Providers.all.getOrNull(p[2].toInt())
                        if (api != null && !s.disabledProviders.remove(api.name)) s.disabledProviders.add(api.name)
                        Store.markDirty(); tg.answerCallback(id); providerSettings(chatId, messageId, user, p[3].toInt())
                    }
                    "pall" -> {
                        if (p[2] == "1") s.disabledProviders.clear() else s.disabledProviders.addAll(Providers.all.map { it.name })
                        Store.markDirty(); tg.answerCallback(id); providerSettings(chatId, messageId, user, p[3].toInt())
                    }
                    else -> tg.answerCallback(id)
                }
            }
            else -> tg.answerCallback(id)
        }
    }
}
