package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.bot.Ui.h
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * "Shazam for novels": everything the bot has put into the dump channel can be found by
 * every user in a second - by typing a name in the bot chat or inline (`@bot name` in any chat).
 *
 * The books are NOT uploaded again: the bot copies the channel message / reuses Telegram's
 * file_id, so sending a 40 MB novel costs no CPU, RAM or bandwidth on the server.
 */
class LibraryBot(
    private val tg: TelegramApi,
    private val botName: () -> String,
    private val isAdmin: (Long) -> Boolean,
    /** admin only: search the websites (the normal scraper search) */
    private val webSearch: suspend (chatId: Long, userId: Long, query: String) -> Unit,
) {
    private data class LibSession(val id: String, val title: String, val ids: List<String>, val query: String?)

    private val sessions = object : LinkedHashMap<String, LibSession>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LibSession>?) = size > 500
    }
    private val counter = AtomicLong(System.currentTimeMillis() % 1_000_000)
    private val perPage = 8

    /** small anti-flood: last send / search per user */
    private val lastSend = ConcurrentHashMap<Long, Long>()
    private val lastSearch = ConcurrentHashMap<Long, Long>()
    /** at most a few deliveries at the same time (keeps us far away from Telegram's flood limits) */
    private val sendSlots = Semaphore(3)

    private fun newSession(title: String, ids: List<String>, query: String?): LibSession {
        val s = LibSession(counter.incrementAndGet().toString(36), title, ids, query)
        synchronized(sessions) { sessions[s.id] = s }
        return s
    }

    private fun session(id: String) = synchronized(sessions) { sessions[id] }

    // ------------------------------------------------------------------ screens

    fun homeRows(admin: Boolean): List<List<Btn>> = listOfNotNull(
        listOf(Btn("🔍 Search novels", inline = "")),
        listOf(Btn("🆕 New", "qn"), Btn("🔥 Popular", "qp")),
        listOf(Btn("🏷 Genres", "qt"), Btn("🎲 Random", "qr")),
        if (admin) listOf(Btn("🏠 Admin menu", "home")) else null,
    )

    suspend fun home(chatId: Long, messageId: Long?, userName: String?, admin: Boolean) {
        val text = """👋 <b>Hi ${h(userName ?: "reader")}!</b>

📚 I have <b>${Library.size}</b> complete novels ready for you.

🔍 <b>Just send me a novel name</b> — I find it in a second, even with typos or short forms:
<code>shadow slave</code> · <code>lotm</code> · <code>tbate</code> · <code>martial peak</code>

✨ Smart filters:
<code>#romance</code> genre · <code>author: name</code> · <code>&gt;500</code> / <code>&lt;100</code> chapters
e.g. <code>#system #reincarnation &gt;300</code>

⚡ Inline mode: type <code>@${h(botName())} name</code> in <b>any</b> chat and send the book directly.

Tap <b>📥 Send Novel</b> and the complete book arrives instantly."""
        val kb = keyboard(homeRows(admin))
        if (messageId == null || !tg.editMessage(chatId, messageId, text, kb)) tg.sendMessage(chatId, text, kb)
    }

    fun help(admin: Boolean): String = """❓ <b>How to find a novel</b>

1️⃣ Send the name (or part of it) — typos, missing words, word order and short forms like <code>lotm</code> are fine
2️⃣ Tap a result → cover + info
3️⃣ Tap <b>📥 Send Novel</b> → the complete book arrives instantly

<b>Filters</b> (combine them freely)
<code>#fantasy</code> — genre / tag
<code>author: Guiltythree</code> or <code>by: name</code> — author
<code>&gt;1000</code> / <code>&lt;200</code> — number of chapters

<b>Commands</b>
/new — newest novels · /popular — most downloaded
/genres — browse by genre · /random — surprise me

⚡ <b>Inline</b>: type <code>@${h(botName())} novel name</code> in any chat.""" +
            (if (admin) "\n\n👮 <b>Admin</b>\n/library — library stats\n<code>/libremove id</code> — remove a novel\nForward book files from the dump channel to me to add old novels to the library.\nNew files posted in the dump channel are added automatically." else "")

    /** Text search inside the library (instant, no site requests) */
    suspend fun search(chatId: Long, userId: Long, query: String, replyTo: Long?) {
        val q = query.trim().take(120)
        if (q.length < 2) { tg.sendMessage(chatId, "Please send at least 2 characters 🙂"); return }
        val now = System.currentTimeMillis()
        if (!isAdmin(userId) && now - (lastSearch[userId] ?: 0) < 1200) return // flood protection
        lastSearch[userId] = now
        Store.stats.searches++; Store.markDirty()

        val hits = Library.search(q, 80)
        val admin = isAdmin(userId)
        if (hits.isEmpty()) {
            val sug = Library.suggest(q, 5)
            val rows = mutableListOf<List<Btn>>()
            sug.forEach { rows += listOf(Btn("📖 ${Ui.cut(it.title, 50)}", "qo:${it.id}")) }
            if (admin) rows += listOf(Btn("🌐 Search websites", "qw:${newSession(q, emptyList(), q).id}"))
            rows += listOf(Btn("🆕 New novels", "qn"), Btn("🏷 Genres", "qt"))
            tg.sendMessage(chatId,
                "😕 <b>${h(q)}</b> is not in the library yet." +
                        (if (sug.isNotEmpty()) "\n\n💡 <b>Did you mean:</b>" else "\n\nTry a shorter name, the English title or a genre like <code>#romance</code>."),
                keyboard(rows), replyTo)
            return
        }
        // a single clear winner -> open it right away (fastest for the user)
        val top = hits.first()
        if (hits.size == 1 || (top.score >= 95 && hits[1].score < 80)) {
            card(chatId, top.novel, admin)
            return
        }
        val s = newSession("🔎 $q", hits.map { it.novel.id }, q)
        showList(chatId, null, s, 0, admin)
    }

    private suspend fun showList(chatId: Long, messageId: Long?, s: LibSession, page: Int, admin: Boolean) {
        val items = s.ids.mapNotNull { Library.get(it) }
        val pages = ((items.size + perPage - 1) / perPage).coerceAtLeast(1)
        val p = page.coerceIn(0, pages - 1)
        val slice = items.drop(p * perPage).take(perPage)
        val sb = StringBuilder("<b>${h(Ui.cut(s.title, 60))}</b> — ${items.size} novel${if (items.size == 1) "" else "s"}\n\n")
        slice.forEachIndexed { i, n ->
            sb.append("<b>${p * perPage + i + 1}.</b> ").append(h(Ui.cut(n.title, 70))).append('\n')
            val meta = listOfNotNull(
                n.author?.let { "✍️ ${Ui.cut(it, 30)}" },
                if (n.chapters > 0) "📚 ${n.chapters} ch" else null,
                n.status?.lowercase()?.replaceFirstChar { it.uppercase() },
                if (n.downloads > 0) "📥 ${Ui.number(n.downloads)}" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) sb.append("     <i>").append(h(meta)).append("</i>\n")
        }
        val nums = slice.mapIndexed { i, n -> Btn("${p * perPage + i + 1}", "qo:${n.id}") }
        val rows = mutableListOf<List<Btn>>()
        rows += nums.take(4); rows += nums.drop(4)
        val nav = mutableListOf<Btn>()
        if (p > 0) nav += Btn("⬅️", "qq:${s.id}:${p - 1}")
        if (pages > 1) nav += Btn("${p + 1}/$pages", "noop")
        if (p < pages - 1) nav += Btn("➡️", "qq:${s.id}:${p + 1}")
        rows += nav
        if (admin && s.query != null) rows += listOf(Btn("🌐 Search websites too", "qw:${s.id}"))
        rows += listOf(Btn("🔍 Search inline", inline = s.query ?: ""), Btn("🏠 Home", "qh"))
        val text = sb.toString()
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private fun cardText(n: LibNovel, max: Int): String {
        val sb = StringBuilder("📖 <b>${h(Ui.cut(n.title, 200))}</b>\n")
        n.author?.let { sb.append("✍️ ").append(h(Ui.cut(it, 80))).append('\n') }
        val line = listOfNotNull(
            if (n.chapters > 0) "📚 <b>${n.chapters}</b> chapters" else null,
            n.status?.let { "🔖 $it" },
        )
        if (line.isNotEmpty()) sb.append(line.joinToString(" · ")).append('\n')
        val files = n.fileIds.size.coerceAtLeast(n.messageIds.size)
        sb.append("📦 ${n.format ?: "Book"}").append(if (files > 1) " · $files volumes" else "")
            .append(if (n.downloads > 0) " · 📥 ${Ui.number(n.downloads)}" else "").append('\n')
        if (n.tags.isNotEmpty()) sb.append("🏷 <i>").append(h(Ui.cut(n.tags.take(8).joinToString(" · "), 160))).append("</i>\n")
        val head = sb.toString()
        val syn = n.synopsis?.replace(Regex("\n{3,}"), "\n\n")?.trim()
        if (!syn.isNullOrBlank()) {
            val room = max - head.length - 40
            if (room > 80) return head + "\n<blockquote expandable>" + h(Ui.cut(syn, room)) + "</blockquote>"
        }
        return head
    }

    private fun cardKeyboard(n: LibNovel, admin: Boolean): Map<String, Any> = keyboard(listOfNotNull(
        listOf(Btn("📥 Send Novel", "qg:${n.id}")),
        listOf(Btn("🔍 Search more", inline = ""), Btn("📤 Share", inline = n.title.take(60))),
        n.tags.firstOrNull()?.let { listOf(Btn("🏷 More ${Ui.cut(it, 20)}", "qx:${it.take(40)}")) },
        if (admin) listOf(Btn("🗑 Remove from library", "qd:${n.id}")) else null,
    ))

    /** Novel card: cover (re-used file_id, no upload) + info + 📥 Send Novel button */
    suspend fun card(chatId: Long, n: LibNovel, admin: Boolean) {
        val kb = cardKeyboard(n, admin)
        n.coverFileId?.let { fid ->
            val ok = runCatching { tg.sendCached("sendPhoto", chatId, fid, cardText(n, 1024), kb) }.isSuccess
            if (ok) return
        }
        tg.sendMessage(chatId, cardText(n, 4000), kb)
    }

    /**
     * Sends the complete novel: copies the channel messages (keeps caption + thumbnail) or
     * re-sends the stored file_id. Nothing is uploaded or built again.
     */
    suspend fun deliver(chatId: Long, userId: Long, n: LibNovel, callbackId: String?): Boolean {
        val now = System.currentTimeMillis()
        if (!isAdmin(userId) && now - (lastSend[userId] ?: 0) < 6000) {
            callbackId?.let { tg.answerCallback(it, "⏳ Please wait a few seconds…") }
            return false
        }
        lastSend[userId] = now
        callbackId?.let { tg.answerCallback(it, "📥 Sending ${Ui.cut(n.title, 40)}…") }
        var sent = 0
        sendSlots.withPermit {
            val count = maxOf(n.messageIds.size, n.fileIds.size)
            for (i in 0 until count) {
                val ok = (n.chatId != 0L && i < n.messageIds.size &&
                        runCatching { tg.copyMessage(chatId, n.chatId, n.messageIds[i]) }
                            .onFailure { Logger.debug("copy ${n.title} vol ${i + 1} failed", it) }.isSuccess) ||
                        (i < n.fileIds.size && runCatching {
                            tg.sendCached("sendDocument", chatId, n.fileIds[i],
                                "📖 <b>${h(Ui.cut(n.title, 200))}</b>" + (if (count > 1) " · Vol ${i + 1}/$count" else "") + "\n🤖 @${botName()}")
                        }.onFailure { Logger.debug("file_id ${n.title} vol ${i + 1} failed", it) }.isSuccess)
                if (ok) sent++
                if (count > 1) delay(350)
            }
        }
        if (sent == 0) {
            tg.sendMessage(chatId, "⚠️ Sorry, <b>${h(Ui.cut(n.title, 80))}</b> is not available right now. The admin was informed.")
            Config.adminIds.forEach { a ->
                runCatching { tg.sendMessage(a, "⚠️ Library: could not send <b>${h(n.title)}</b> (id <code>${n.id}</code>). Message deleted from the channel? <code>/libremove ${n.id}</code>") }
            }
            return false
        }
        Library.countDownload(n.id)
        Store.stats.downloads++; Store.markDirty()
        return true
    }

    // ------------------------------------------------------------------ lists

    private suspend fun listNew(chatId: Long, messageId: Long?, admin: Boolean) {
        val s = newSession("🆕 Newest novels", Library.latest(200).map { it.id }, null)
        if (s.ids.isEmpty()) { tg.sendMessage(chatId, "📭 The library is still empty — new novels arrive every few minutes."); return }
        showList(chatId, messageId, s, 0, admin)
    }

    private suspend fun listPopular(chatId: Long, messageId: Long?, admin: Boolean) {
        val s = newSession("🔥 Most downloaded", Library.popular(200).map { it.id }, null)
        if (s.ids.isEmpty()) { tg.sendMessage(chatId, "📭 The library is still empty."); return }
        showList(chatId, messageId, s, 0, admin)
    }

    private suspend fun genres(chatId: Long, messageId: Long?) {
        val tags = Library.topTags(30)
        if (tags.isEmpty()) { tg.sendMessage(chatId, "No genres yet."); return }
        val rows = tags.map { (t, c) -> Btn("${Ui.cut(t.replaceFirstChar { it.uppercase() }, 18)} ($c)", "qx:${t.take(40)}") }
            .chunked(2).toMutableList()
        rows += listOf(Btn("🏠 Home", "qh"))
        val text = "🏷 <b>Genres</b>\nPick one — or search like <code>#romance #system</code>"
        if (messageId == null || !tg.editMessage(chatId, messageId, text, keyboard(rows))) tg.sendMessage(chatId, text, keyboard(rows))
    }

    private suspend fun byTag(chatId: Long, messageId: Long?, tag: String, admin: Boolean) {
        val list = Library.byTag(tag)
        if (list.isEmpty()) { tg.sendMessage(chatId, "Nothing found for #${h(tag)}"); return }
        showList(chatId, messageId, newSession("🏷 ${tag.replaceFirstChar { it.uppercase() }}", list.map { it.id }, "#${tag.replace(" ", "")}"), 0, admin)
    }

    suspend fun random(chatId: Long, admin: Boolean) {
        val n = Library.random(1).firstOrNull() ?: run { tg.sendMessage(chatId, "📭 The library is still empty."); return }
        card(chatId, n, admin)
    }

    suspend fun stats(chatId: Long) {
        val total = Library.size
        val top = Library.popular(5).filter { it.downloads > 0 }
        tg.sendMessage(chatId, "📚 <b>Library</b>: $total novels in the dump channel\n" +
                "🏷 Genres: ${Library.topTags(8).joinToString { "${it.first} (${it.second})" }.ifEmpty { "-" }}\n" +
                (if (top.isNotEmpty()) "\n🔥 <b>Most downloaded</b>\n" + top.joinToString("\n") { "• ${h(Ui.cut(it.title, 50))} — ${it.downloads}" } else ""))
    }

    // ------------------------------------------------------------------ commands / callbacks

    /** Returns true when the command belongs to the library */
    suspend fun command(cmd: String, arg: String, chatId: Long, userId: Long, userName: String?, msgId: Long): Boolean {
        val admin = isAdmin(userId)
        when (cmd) {
            "new", "latest" -> listNew(chatId, null, admin)
            "popular", "top" -> listPopular(chatId, null, admin)
            "genres", "genre", "tags" -> if (arg.isBlank()) genres(chatId, null) else byTag(chatId, null, arg, admin)
            "random" -> random(chatId, admin)
            "find", "novel", "n" -> if (arg.isBlank()) tg.sendMessage(chatId, "Send <code>/find novel name</code> or just type the name 🙂") else search(chatId, userId, arg, msgId)
            "library", "lib" -> if (admin) stats(chatId) else home(chatId, null, userName, false)
            "libremove" -> if (admin) {
                val n = Library.remove(arg.trim())
                Library.save(force = true)
                tg.sendMessage(chatId, if (n == null) "Not found. Usage: <code>/libremove id</code>" else "🗑 Removed <b>${h(n.title)}</b> from the library.")
            } else return false
            else -> return false
        }
        return true
    }

    /** Handles q* callbacks. Returns true when handled. */
    suspend fun callback(id: String, data: String, chatIdRaw: Long, messageId: Long, isPhoto: Boolean, userId: Long, userName: String?): Boolean {
        if (!data.startsWith("q")) return false
        val p = data.split(':', limit = 3)
        val admin = isAdmin(userId)
        // buttons under inline messages have no chat -> answer in the user's private chat
        val chatId = if (chatIdRaw == 0L) userId else chatIdRaw
        val textMid = if (isPhoto || chatIdRaw == 0L) null else messageId
        when (p[0]) {
            "qh" -> { tg.answerCallback(id); home(chatId, textMid, userName, admin) }
            "qn" -> { tg.answerCallback(id); listNew(chatId, textMid, admin) }
            "qp" -> { tg.answerCallback(id); listPopular(chatId, textMid, admin) }
            "qt" -> { tg.answerCallback(id); genres(chatId, textMid) }
            "qr" -> { tg.answerCallback(id, "🎲"); random(chatId, admin) }
            "qx" -> { tg.answerCallback(id); byTag(chatId, textMid, p.getOrElse(1) { "" }, admin) }
            "qq" -> {
                val s = session(p.getOrElse(1) { "" }) ?: run { tg.answerCallback(id, "⌛ Expired, please search again"); return true }
                tg.answerCallback(id); showList(chatId, textMid, s, p.getOrNull(2)?.toIntOrNull() ?: 0, admin)
            }
            "qo" -> {
                val n = Library.get(p.getOrElse(1) { "" }) ?: run { tg.answerCallback(id, "Not available any more"); return true }
                tg.answerCallback(id, "📖 ${Ui.cut(n.title, 40)}"); card(chatId, n, admin)
            }
            "qg" -> {
                val n = Library.get(p.getOrElse(1) { "" }) ?: run { tg.answerCallback(id, "Not available any more", true); return true }
                deliver(chatId, userId, n, id)
            }
            "qd" -> {
                if (!admin) { tg.answerCallback(id); return true }
                val n = Library.remove(p.getOrElse(1) { "" }); Library.save(force = true)
                tg.answerCallback(id, if (n == null) "Already removed" else "🗑 Removed from the library")
            }
            "qw" -> {
                if (!admin) { tg.answerCallback(id); return true }
                val s = session(p.getOrElse(1) { "" }) ?: run { tg.answerCallback(id, "⌛ Expired"); return true }
                tg.answerCallback(id, "🌐 Searching the websites…")
                webSearch(chatId, userId, s.query ?: s.title)
            }
            else -> tg.answerCallback(id)
        }
        return true
    }

    // ------------------------------------------------------------------ inline mode

    /**
     * `@bot shadow slave` in any chat: the matching books are offered as cached documents -
     * tapping one sends the complete book right there (like @VoiceShazamBot does with songs).
     */
    suspend fun inlineQuery(q: JsonNode) {
        val id = q.path("id").asText()
        val query = q.path("query").asText("").trim()
        val offset = q.path("offset").asText("").toIntOrNull() ?: 0
        val list: List<LibNovel> = if (query.isBlank()) Library.latest(50, offset).also { } else
            Library.search(query, 200).map { it.novel }.drop(offset)
        val page = list.take(20)
        val results = page.mapNotNull { n ->
            val fid = n.fileIds.firstOrNull() ?: return@mapNotNull null
            val vols = n.fileIds.size
            val deep = "https://t.me/${botName()}?start=g_${n.id}"
            mapOf(
                "type" to "document", "id" to n.id, "title" to n.title, "document_file_id" to fid,
                "description" to listOfNotNull(
                    n.author?.let { "✍️ $it" }, if (n.chapters > 0) "📚 ${n.chapters} ch" else null,
                    if (vols > 1) "📦 $vols volumes" else null, n.tags.take(3).joinToString(", ").ifBlank { null },
                ).joinToString(" · "),
                "caption" to "📖 <b>${h(Ui.cut(n.title, 200))}</b>" + (if (vols > 1) " · Vol 1/$vols" else "") +
                        (n.author?.let { "\n✍️ ${h(Ui.cut(it, 80))}" } ?: "") +
                        (if (n.chapters > 0) "\n📚 ${n.chapters} chapters" else "") + "\n🤖 @${botName()}",
                "parse_mode" to "HTML",
                "reply_markup" to keyboard(listOfNotNull(
                    if (vols > 1) listOf(Btn("📥 Get all $vols volumes", url = deep)) else listOf(Btn("📚 More novels", url = "https://t.me/${botName()}?start=lib")),
                    listOf(Btn("🔍 Search novels", inline = "")),
                )),
            )
        }
        val next = if (list.size > 20 && offset + 20 < 200) (offset + 20).toString() else null
        val button = if (results.isEmpty() && offset == 0) mapOf("text" to "😕 Nothing found — open the bot", "start_parameter" to "lib") else null
        tg.answerInlineQuery(id, results, next, cacheTime = if (query.isBlank()) 30 else 120, button = button)
    }

    // ------------------------------------------------------------------ indexing

    /**
     * A message in the dump channel (posted by somebody else than the bot) or a book file the
     * admin forwarded to the bot: every EPUB / TXT / HTML / PDF / ZIP document is added to the library.
     */
    fun indexDocument(m: JsonNode, chatId: Long): LibNovel? {
        val doc = m.path("document")
        if (doc.isMissingNode || doc.isNull) return null
        val name = doc.path("file_name").asText("book")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("epub", "txt", "html", "htm", "pdf", "zip", "mobi", "azw3", "fb2", "docx")) return null
        val fileId = doc.path("file_id").asText(null) ?: return null
        // forwarded from the channel -> remember the original message, so copyMessage works
        val origin = m.path("forward_origin")
        val (srcChat, srcMsg) = if (origin.path("type").asText() == "channel")
            origin.path("chat").path("id").asLong() to origin.path("message_id").asLong()
        else if (m.path("chat").path("type").asText() == "channel") chatId to m.path("message_id").asLong()
        else 0L to 0L
        val n = Library.addFile(srcChat, srcMsg, fileId, name, m.path("caption").asText(null))
        Library.save()
        Logger.info("Library: indexed \"${n.title}\" ($name)")
        return n
    }
}
