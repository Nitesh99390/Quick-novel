package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.bot.Ui.h
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the bot ever sent to the dump channel, searchable by every user ("Shazam for novels").
 *
 * A user types a name (typos and short forms work), picks the novel and taps 📥 Send Novel:
 * the complete book is *copied* from the channel - no scraping, no building, no upload,
 * so it costs no CPU / RAM and arrives instantly.
 *
 * Callback data (all start with "q", routed here by Bot.onCallback):
 *   qh                home            qr               random novel
 *   qn:<p> / qp:<p>   newest / popular list page
 *   qg:<p>            genre list      qG:<genre>:<p>   novels of a genre
 *   qs:<sid>:<p>      search result page
 *   qo:<id>           novel card      qd:<id>          send the novel
 *   qw:<sid>          admin: search the websites instead
 *   qx:<id> / qX:<id> admin: remove from library (ask / confirm)
 */
class LibraryBot(
    private val tg: TelegramApi,
    private val botName: () -> String,
    private val isAdmin: (Long) -> Boolean,
    /** (chat, user, query) -> searches the websites (admins only) */
    private val siteSearch: suspend (Long, Long, String) -> Unit,
) {
    private val pageSize = 8

    private data class Results(val query: String, val ids: List<String>)

    private val sessions = object : LinkedHashMap<String, Results>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Results>?) = size > 500
    }
    private val sessionIds = AtomicLong(System.currentTimeMillis() % 1_000_000)

    /** short key -> genre name (callback_data is limited to 64 bytes) */
    private val genreKeys = ConcurrentHashMap<String, String>()

    /** users with a delivery in progress + time of their last delivery (simple flood protection) */
    private val delivering = ConcurrentHashMap.newKeySet<Long>()
    private val lastDelivery = ConcurrentHashMap<Long, Long>()

    private fun newSession(query: String, ids: List<String>): String {
        val id = java.lang.Long.toString(sessionIds.incrementAndGet(), 36)
        synchronized(sessions) { sessions[id] = Results(query, ids) }
        return id
    }

    private fun session(id: String): Results? = synchronized(sessions) { sessions[id] }

    private fun genreKey(name: String): String {
        val k = Integer.toHexString(Library.norm(name).hashCode())
        genreKeys[k] = name
        return k
    }

    // ------------------------------------------------------------------ texts

    private fun line(i: Int, n: LibNovel): String {
        val meta = listOfNotNull(
            n.author?.let { Ui.cut(it, 30) },
            n.chapters.takeIf { it > 0 }?.let { "$it ch" },
            n.status?.lowercase()?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
        return "<b>$i.</b> ${h(Ui.cut(n.title, 70))}" + (if (meta.isNotEmpty()) "\n     <i>${h(meta)}</i>" else "")
    }

    fun homeRows(admin: Boolean): List<List<Btn>> = listOfNotNull(
        listOf(Btn("🆕 New", "qn:0"), Btn("🔥 Popular", "qp:0")),
        listOf(Btn("🏷 Genres", "qg:0"), Btn("🎲 Random", "qr")),
        listOf(Btn("🔍 Inline search", inline = "")),
        if (admin) listOf(Btn("🏠 Bot menu", "home")) else null,
    )

    fun help(admin: Boolean): String = """❓ <b>How to find a novel</b>

Just send me the name of a novel. Typos and short forms are fine:
• <code>shadow slave</code> · <code>shadowslave</code> · <code>shadw slav</code>
• <code>lotm</code> → Lord of the Mysteries
• an author name, e.g. <code>cuttlefish</code>

Tap the novel, then <b>📥 Send Novel</b> - you get the complete book instantly.

<b>Commands</b>
/new - newest novels
/popular - most downloaded novels
/genres - browse by genre
/random - a random novel
/search <i>name</i> - search

🔍 <b>Inline:</b> type <code>@${botName()} name</code> in any chat.""" +
            (if (admin) "\n\n👮 <b>Admin</b>\n/library - library stats\n/libremove <i>name or id</i> - remove a novel\n" +
                    "Forward book files from the dump channel to me to add them to the library." else "")

    suspend fun home(chatId: Long, messageId: Long?, name: String?, admin: Boolean) {
        val text = "📚 <b>Novel Library</b>\n\n" +
                (name?.let { "Hi ${h(it)}! " } ?: "") +
                "I have <b>${Library.size}</b> complete novels.\n\n" +
                "🔍 <b>Send me a novel name</b> to find it - typos and short forms (like <code>lotm</code>) work.\n" +
                "Then tap <b>📥 Send Novel</b> and the full book arrives instantly."
        show(chatId, messageId, false, text, homeRows(admin))
    }

    /** edits the message when possible, otherwise sends a new one */
    private suspend fun show(chatId: Long, messageId: Long?, isPhoto: Boolean, text: String, rows: List<List<Btn>>) {
        val kb = keyboard(rows)
        if (messageId != null && !isPhoto && tg.editMessage(chatId, messageId, text, kb)) return
        tg.sendMessage(chatId, text, kb)
    }

    // ------------------------------------------------------------------ lists

    private suspend fun showList(
        chatId: Long, messageId: Long?, isPhoto: Boolean, title: String, novels: List<LibNovel>, page: Int,
        nav: (Int) -> String, extra: List<List<Btn>> = emptyList(),
    ) {
        if (novels.isEmpty()) {
            show(chatId, messageId, isPhoto, "$title\n\n📭 Nothing here yet.", extra + listOf(listOf(Btn("📚 Library", "qh"))))
            return
        }
        val pages = (novels.size + pageSize - 1) / pageSize
        val p = page.coerceIn(0, pages - 1)
        val slice = novels.drop(p * pageSize).take(pageSize)
        val text = "$title\n\n" + slice.mapIndexed { i, n -> line(p * pageSize + i + 1, n) }.joinToString("\n") +
                (if (pages > 1) "\n\n📄 Page ${p + 1}/$pages · ${novels.size} novels" else "")
        val numbers = slice.mapIndexed { i, n -> Btn("${p * pageSize + i + 1}", "qo:${n.id}") }.chunked(4)
        val navRow = listOfNotNull(
            if (p > 0) Btn("⬅️", nav(p - 1)) else null,
            if (pages > 1) Btn("${p + 1}/$pages", "noop") else null,
            if (p < pages - 1) Btn("➡️", nav(p + 1)) else null,
        )
        show(chatId, messageId, isPhoto, text, numbers + listOf(navRow) + extra + listOf(listOf(Btn("📚 Library", "qh"))))
    }

    private suspend fun genres(chatId: Long, messageId: Long?, isPhoto: Boolean, page: Int) {
        val all = Library.genres()
        if (all.isEmpty()) {
            show(chatId, messageId, isPhoto, "🏷 <b>Genres</b>\n\n📭 No genres yet.", listOf(listOf(Btn("📚 Library", "qh"))))
            return
        }
        val per = 24
        val pages = (all.size + per - 1) / per
        val p = page.coerceIn(0, pages - 1)
        val rows = all.drop(p * per).take(per).map { (g, c) -> Btn("${Ui.cut(g, 22)} ($c)", "qG:${genreKey(g)}:0") }.chunked(2)
        val navRow = listOfNotNull(
            if (p > 0) Btn("⬅️", "qg:${p - 1}") else null,
            if (pages > 1) Btn("${p + 1}/$pages", "noop") else null,
            if (p < pages - 1) Btn("➡️", "qg:${p + 1}") else null,
        )
        show(chatId, messageId, isPhoto, "🏷 <b>Genres</b> · ${all.size}\n\nPick a genre:", rows + listOf(navRow) + listOf(listOf(Btn("📚 Library", "qh"))))
    }

    // ------------------------------------------------------------------ search

    suspend fun search(chatId: Long, userId: Long, query: String, replyTo: Long?) {
        val q = query.trim().take(100)
        if (Library.norm(q).isEmpty()) { tg.sendMessage(chatId, "🔍 Send me a novel name, e.g. <code>Shadow Slave</code>"); return }
        Store.stats.searches++
        Store.markDirty()
        val admin = isAdmin(userId)
        val hits = Library.search(q, 60)
        if (hits.isEmpty()) {
            val sug = Library.suggest(q)
            val sid = newSession(q, sug.map { it.id })
            val rows = sug.map { listOf(Btn("💡 ${Ui.cut(it.title, 40)}", "qo:${it.id}")) } +
                    listOfNotNull(if (admin) listOf(Btn("🌐 Search the websites", "qw:$sid")) else null) +
                    listOf(listOf(Btn("📚 Library", "qh")))
            tg.sendMessage(
                chatId,
                "😕 <b>${h(Ui.cut(q, 60))}</b> is not in the library yet." +
                        (if (sug.isNotEmpty()) "\n\nDid you mean:" else "\n\nCheck the spelling or try a shorter name.") +
                        (if (!admin) "\n\n🤖 New novels are added every day - try again later." else ""),
                keyboard(rows), replyTo,
            )
            return
        }
        // one clear winner -> open it directly
        val top = hits[0]
        if (top.score >= 88 && (hits.size == 1 || hits[1].score < top.score - 15)) {
            card(chatId, top.novel, admin)
            return
        }
        val sid = newSession(q, hits.map { it.novel.id })
        showList(
            chatId, null, false, "🔍 <b>${h(Ui.cut(q, 60))}</b> · ${hits.size} found", hits.map { it.novel }, 0,
            nav = { "qs:$sid:$it" },
            extra = if (admin) listOf(listOf(Btn("🌐 Search the websites", "qw:$sid"))) else emptyList(),
        )
    }

    // ------------------------------------------------------------------ novel card + delivery

    private fun cardText(n: LibNovel, maxLen: Int): String {
        val sb = StringBuilder("📖 <b>").append(h(Ui.cut(n.title, 200))).append("</b>\n")
        n.author?.let { sb.append("✍️ ").append(h(Ui.cut(it, 80))).append('\n') }
        val meta = listOfNotNull(
            n.chapters.takeIf { it > 0 }?.let { "📚 <b>$it</b> chapters" },
            n.status?.let { "🔖 ${h(it)}" },
        )
        if (meta.isNotEmpty()) sb.append(meta.joinToString(" · ")).append('\n')
        val files = n.messageIds.size.coerceAtLeast(n.fileIds.size)
        sb.append("📦 ").append(n.format ?: "Book").append(if (files > 1) " · $files volumes" else "")
        if (n.downloads > 0) sb.append(" · 📥 ${Ui.number(n.downloads)}")
        sb.append('\n')
        if (n.tags.isNotEmpty()) sb.append("🏷 <i>").append(h(Ui.cut(n.tags.take(10).joinToString(" · "), 200))).append("</i>\n")
        val head = sb.toString()
        val syn = n.synopsis?.replace(Regex("\n{3,}"), "\n\n")?.trim()
        if (!syn.isNullOrBlank()) {
            val room = maxLen - head.length - 40
            if (room > 80) return head + "\n<blockquote expandable>" + h(Ui.cut(syn, room)) + "</blockquote>"
        }
        return head
    }

    private fun cardRows(n: LibNovel, admin: Boolean): List<List<Btn>> = listOfNotNull(
        listOf(Btn("📥 Send Novel", "qd:${n.id}")),
        listOf(Btn("↗️ Share", url = "https://t.me/share/url?url=" +
                java.net.URLEncoder.encode("https://t.me/${botName()}?start=o_${n.id}", "UTF-8"))),
        if (admin) listOf(Btn("🗑 Remove from library", "qx:${n.id}")) else null,
        listOf(Btn("📚 Library", "qh")),
    )

    suspend fun card(chatId: Long, n: LibNovel, admin: Boolean, messageId: Long? = null, isPhoto: Boolean = false) {
        val rows = cardRows(n, admin)
        val cover = n.coverFileId
        if (cover != null) {
            val ok = runCatching { tg.sendCached("sendPhoto", chatId, cover, cardText(n, 1024), keyboard(rows)) }.isSuccess
            if (ok) return
        }
        show(chatId, messageId, isPhoto, cardText(n, 3800), rows)
    }

    /** Sends the complete novel: copies the files from the dump channel (no upload, no CPU) */
    suspend fun deliver(chatId: Long, userId: Long, n: LibNovel, callbackId: String?) {
        val admin = isAdmin(userId)
        val wait = (lastDelivery[userId] ?: 0L) + 8_000 - System.currentTimeMillis()
        if (!admin && wait > 0) {
            callbackId?.let { tg.answerCallback(it, "⏳ Please wait ${wait / 1000 + 1}s", false) }
                ?: tg.sendMessage(chatId, "⏳ Please wait ${wait / 1000 + 1}s")
            return
        }
        if (!delivering.add(userId)) {
            callbackId?.let { tg.answerCallback(it, "⏳ Already sending a novel…") }
            return
        }
        try {
            callbackId?.let { tg.answerCallback(it, "📥 Sending ${Ui.cut(n.title, 40)}…") }
            tg.sendChatAction(chatId, "upload_document")
            val count = n.messageIds.size.coerceAtLeast(n.fileIds.size)
            var sent = 0
            for (i in 0 until count) {
                val viaCopy = n.chatId != 0L && i < n.messageIds.size && runCatching {
                    tg.copyMessage(chatId, n.chatId, n.messageIds[i])
                }.onFailure { Logger.debug("copy ${n.title} #$i failed", it) }.isSuccess
                val ok = viaCopy || (i < n.fileIds.size && runCatching {
                    tg.sendCached("sendDocument", chatId, n.fileIds[i], fileCaption(n, i, count))
                }.onFailure { Logger.debug("resend ${n.title} #$i failed", it) }.isSuccess)
                if (ok) sent++
                if (count > 1) delay(700)
            }
            if (sent == 0) {
                tg.sendMessage(chatId, "⚠️ Sorry, <b>${h(n.title)}</b> is not available any more." +
                        (if (admin) "\nThe channel message was deleted or the bot is not admin there. /libremove ${n.id}" else ""))
                return
            }
            Library.countDownload(n.id)
            Store.stats.downloads++
            Store.markDirty()
            lastDelivery[userId] = System.currentTimeMillis()
            if (sent < count) tg.sendMessage(chatId, "⚠️ $sent of $count volumes could be sent.")
        } finally {
            delivering.remove(userId)
        }
    }

    private fun fileCaption(n: LibNovel, i: Int, count: Int): String =
        "📖 <b>${h(Ui.cut(n.title, 200))}</b>" + (if (count > 1) " · Vol ${i + 1}/$count" else "") + "\n🤖 @${botName()}"

    // ------------------------------------------------------------------ commands + callbacks

    /** Library commands for everybody. Returns false when [cmd] is not a library command. */
    suspend fun command(cmd: String, arg: String, chatId: Long, userId: Long, name: String?, msgId: Long): Boolean {
        val admin = isAdmin(userId)
        when (cmd) {
            "new", "latest" -> showList(chatId, null, false, "🆕 <b>Newest novels</b>", Library.newest(), 0, { "qn:$it" })
            "popular", "top" -> showList(chatId, null, false, "🔥 <b>Most downloaded</b>", Library.popular(), 0, { "qp:$it" })
            "genres", "genre", "tags" -> if (arg.isBlank()) genres(chatId, null, false, 0) else {
                val g = Library.genres().firstOrNull { Library.norm(it.first) == Library.norm(arg) }?.first
                if (g == null) genres(chatId, null, false, 0)
                else showList(chatId, null, false, "🏷 <b>${h(g)}</b>", Library.byGenre(g), 0, { "qG:${genreKey(g)}:$it" })
            }
            "random" -> Library.random()?.let { card(chatId, it, admin) } ?: tg.sendMessage(chatId, "📭 The library is empty.")
            "find", "novel" -> if (arg.isBlank()) tg.sendMessage(chatId, "🔍 Send me the novel name, e.g. <code>/find Shadow Slave</code>")
                else search(chatId, userId, arg, msgId)
            "library", "lib" -> if (admin) stats(chatId) else home(chatId, null, name?.substringBefore(' '), false)
            "libremove" -> {
                if (!admin) return false
                val n = Library.get(arg.trim()) ?: Library.search(arg, 1, 85.0).firstOrNull()?.novel
                if (arg.isBlank() || n == null) tg.sendMessage(chatId, "Usage: <code>/libremove name or id</code>")
                else { Library.remove(n.id); Library.save(force = true); tg.sendMessage(chatId, "🗑 Removed <b>${h(n.title)}</b> from the library.") }
            }
            else -> return false
        }
        return true
    }

    private suspend fun stats(chatId: Long) {
        val all = Library.all()
        val downloads = all.sumOf { it.downloads }
        val files = all.sumOf { it.messageIds.size.coerceAtLeast(it.fileIds.size) }
        val noCopy = all.count { it.chatId == 0L }
        val top = Library.popular().take(5).filter { it.downloads > 0 }
        val text = "📚 <b>Library</b>\n\n" +
                "Novels: <b>${all.size}</b> · files: <b>$files</b>\n" +
                "Delivered: <b>$downloads</b> times\n" +
                "Chapters: <b>${all.sumOf { it.chapters }}</b>\n" +
                (if (noCopy > 0) "Only file id (not from a channel): $noCopy\n" else "") +
                "Public: <b>${if (Config.publicLibrary) "yes" else "admins only"}</b>\n" +
                (if (top.isNotEmpty()) "\n🔥 <b>Top</b>\n" + top.mapIndexed { i, n -> "${i + 1}. ${h(Ui.cut(n.title, 50))} · ${n.downloads}" }.joinToString("\n") else "") +
                "\n\n<i>Every novel the auto scraper sends is added automatically. Forward book files from the dump channel to add old ones.</i>"
        tg.sendMessage(chatId, text, keyboard(homeRows(true)))
    }

    suspend fun callback(id: String, data: String, chatId: Long, messageId: Long, isPhoto: Boolean, userId: Long, name: String?) {
        val admin = isAdmin(userId)
        val p = data.split(':')
        val mid: Long? = if (isPhoto) null else messageId
        fun page(i: Int) = p.getOrNull(i)?.toIntOrNull() ?: 0
        when (p[0]) {
            "qh" -> { tg.answerCallback(id); home(chatId, mid, name, admin) }
            "qn" -> { tg.answerCallback(id); showList(chatId, mid, isPhoto, "🆕 <b>Newest novels</b>", Library.newest(), page(1), { "qn:$it" }) }
            "qp" -> { tg.answerCallback(id); showList(chatId, mid, isPhoto, "🔥 <b>Most downloaded</b>", Library.popular(), page(1), { "qp:$it" }) }
            "qg" -> { tg.answerCallback(id); genres(chatId, mid, isPhoto, page(1)) }
            "qG" -> {
                val g = genreKeys[p.getOrNull(1)] ?: run { tg.answerCallback(id, "⌛ Expired, open the genres again"); genres(chatId, mid, isPhoto, 0); return }
                tg.answerCallback(id)
                showList(chatId, mid, isPhoto, "🏷 <b>${h(g)}</b>", Library.byGenre(g), page(2), { "qG:${p[1]}:$it" },
                    listOf(listOf(Btn("🏷 All genres", "qg:0"))))
            }
            "qs" -> {
                val s = session(p.getOrNull(1) ?: "") ?: run { tg.answerCallback(id, "⌛ Expired - send the name again"); return }
                tg.answerCallback(id)
                showList(chatId, mid, isPhoto, "🔍 <b>${h(Ui.cut(s.query, 60))}</b> · ${s.ids.size} found",
                    s.ids.mapNotNull { Library.get(it) }, page(2), { "qs:${p[1]}:$it" },
                    if (admin) listOf(listOf(Btn("🌐 Search the websites", "qw:${p[1]}"))) else emptyList())
            }
            "qr" -> {
                val n = Library.random() ?: run { tg.answerCallback(id, "📭 The library is empty"); return }
                tg.answerCallback(id); card(chatId, n, admin)
            }
            "qo" -> {
                val n = Library.get(p.getOrNull(1) ?: "") ?: run { tg.answerCallback(id, "❌ Not in the library any more", true); return }
                tg.answerCallback(id); card(chatId, n, admin)
            }
            "qd" -> {
                val n = Library.get(p.getOrNull(1) ?: "") ?: run { tg.answerCallback(id, "❌ Not in the library any more", true); return }
                deliver(chatId, userId, n, id)
            }
            "qw" -> {
                if (!admin) { tg.answerCallback(id, "🔒 Admin only", true); return }
                val s = session(p.getOrNull(1) ?: "") ?: run { tg.answerCallback(id, "⌛ Expired - send the name again"); return }
                tg.answerCallback(id, "🌐 Searching the websites…")
                siteSearch(chatId, userId, s.query)
            }
            "qx" -> {
                if (!admin) { tg.answerCallback(id, "🔒 Admin only", true); return }
                val n = Library.get(p.getOrNull(1) ?: "") ?: run { tg.answerCallback(id, "Already removed"); return }
                tg.answerCallback(id)
                tg.sendMessage(chatId, "🗑 Remove <b>${h(n.title)}</b> from the library?\n<i>The files in the channel are not deleted.</i>",
                    keyboard(listOf(listOf(Btn("✅ Yes, remove", "qX:${n.id}"), Btn("❌ No", "qo:${n.id}")))))
            }
            "qX" -> {
                if (!admin) { tg.answerCallback(id, "🔒 Admin only", true); return }
                val n = Library.remove(p.getOrNull(1) ?: "")
                Library.save(force = true)
                tg.answerCallback(id, if (n != null) "🗑 Removed" else "Already removed")
                if (mid != null) tg.editMessage(chatId, mid, "🗑 Removed <b>${h(n?.title ?: "novel")}</b> from the library.", keyboard(listOf(listOf(Btn("📚 Library", "qh")))))
            }
            else -> tg.answerCallback(id)
        }
    }

    // ------------------------------------------------------------------ inline mode

    suspend fun inlineQuery(q: JsonNode) {
        val id = q.path("id").asText()
        val query = q.path("query").asText("").trim()
        val offset = q.path("offset").asText("").toIntOrNull() ?: 0
        val list = if (query.isBlank()) Library.popular() else Library.search(query, 200).map { it.novel }
        val page = list.drop(offset).take(25)
        val bot = botName()
        val results = page.map { n ->
            val single = n.fileIds.size == 1 && n.messageIds.size <= 1
            val desc = listOfNotNull(n.author, n.chapters.takeIf { it > 0 }?.let { "$it chapters" }, n.status).joinToString(" · ")
            val markup = keyboard(listOf(listOf(Btn("📥 Get it from @$bot", url = "https://t.me/$bot?start=g_${n.id}"))))
            if (single) mapOf(
                "type" to "document", "id" to n.id, "title" to Ui.cut(n.title, 100),
                "document_file_id" to n.fileIds[0], "description" to desc.ifBlank { null },
                "caption" to fileCaption(n, 0, 1), "parse_mode" to "HTML",
            ) else mapOf(
                "type" to "article", "id" to n.id, "title" to Ui.cut(n.title, 100),
                "description" to (desc.ifBlank { "Complete novel" } + " · ${n.messageIds.size.coerceAtLeast(n.fileIds.size)} files"),
                "input_message_content" to mapOf("message_text" to cardText(n, 3500), "parse_mode" to "HTML",
                    "link_preview_options" to mapOf("is_disabled" to true)),
                "reply_markup" to markup,
            )
        }
        val next = if (offset + page.size < list.size) (offset + page.size).toString() else null
        val button = if (list.isEmpty()) mapOf("text" to "😕 Not found - open the library", "start_parameter" to "lib")
        else mapOf("text" to "📚 ${Library.size} novels - open the bot", "start_parameter" to "lib")
        tg.answerInlineQuery(id, results, next, cacheTime = if (query.isBlank()) 120 else 30, button = button)
    }

    // ------------------------------------------------------------------ indexing channel files

    private val bookExt = setOf("epub", "txt", "html", "htm", "pdf", "fb2", "mobi", "azw3", "docx", "zip")

    /**
     * Adds a book file (channel post, or a file an admin sends / forwards) to the library.
     * Volumes of the same novel ("… - Vol 02.epub") are merged into one entry.
     * Returns the library entry or null when the message is not a book.
     */
    fun indexDocument(m: JsonNode, chatId: Long): LibNovel? {
        val doc = m.path("document")
        if (doc.isMissingNode || doc.isNull) return null
        val fileName = doc.path("file_name").asText("")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in bookExt) return null
        val fileId = doc.path("file_id").asText(null) ?: return null
        val caption = m.path("caption").asText("")

        // where does the original message live? (channel post / forwarded from a channel / only file id)
        val origin = m.path("forward_origin")
        val (srcChat, srcMsg) = when {
            m.path("chat").path("type").asText() == "channel" -> chatId to m.path("message_id").asLong()
            origin.path("type").asText() == "channel" -> origin.path("chat").path("id").asLong() to origin.path("message_id").asLong()
            m.has("forward_from_chat") && m.path("forward_from_chat").path("type").asText() == "channel" ->
                m.path("forward_from_chat").path("id").asLong() to m.path("forward_from_message_id").asLong()
            else -> 0L to 0L
        }

        val lines = caption.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull()?.replace(Regex("^[^\\p{L}\\p{N}]+"), "")?.trim()?.takeIf { it.length >= 2 && !it.startsWith("#") }
            ?: fileName.substringBeforeLast('.')
                .replace(Regex("\\s*-\\s*Vol\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\(Ch\\s*\\d+-\\d+\\)\\s*$", RegexOption.IGNORE_CASE), "")
                .replace('_', ' ').trim()
        if (title.isBlank()) return null
        val author = lines.firstOrNull { it.startsWith("✍") }?.substringAfter(' ')?.removePrefix("Author:")?.trim()?.ifBlank { null }
        val chapters = Regex("Chapters?\\s*(?:1\\s*[–-]\\s*)?(\\d+)", RegexOption.IGNORE_CASE).find(caption)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val tags = Regex("#([\\p{L}\\p{N}_]+)").findAll(caption).map { it.groupValues[1].replace('_', ' ') }.toList().distinct()
        val vol = Regex("Vol\\s*(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE).find(caption)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("-\\s*Vol\\s*(\\d+)", RegexOption.IGNORE_CASE).find(fileName)?.groupValues?.get(1)?.toIntOrNull()
        val status = Regex("·\\s*(Completed|Ongoing|Hiatus|Dropped|Stubs)", RegexOption.IGNORE_CASE).find(caption)?.groupValues?.get(1)
        val format = when (ext) { "htm" -> "HTML"; else -> ext.uppercase() }

        val existing = Library.findByTitle(title)
        if (existing != null) {
            if (fileId in existing.fileIds || (srcMsg != 0L && srcMsg in existing.messageIds)) return existing
            // another volume of a novel we know -> append it (only when both live in the same place)
            if (vol != null && vol > 1 && (existing.chatId == srcChat || srcChat == 0L)) {
                val names = existing.fileNames + fileName
                val merged = existing.copy(
                    messageIds = if (srcMsg != 0L && existing.chatId == srcChat) existing.messageIds + srcMsg else existing.messageIds,
                    fileIds = existing.fileIds + fileId, fileNames = names,
                    chapters = maxOf(existing.chapters, chapters),
                    tags = existing.tags.ifEmpty { tags },
                )
                Library.put(merged)
                return merged
            }
            // same novel again (newer / complete) -> replace the files, keep the stats
            val replaced = existing.copy(
                chatId = srcChat, messageIds = if (srcMsg != 0L) listOf(srcMsg) else emptyList(), fileIds = listOf(fileId),
                fileNames = listOf(fileName), chapters = maxOf(chapters, if (existing.chatId == srcChat) existing.chapters else 0),
                author = author ?: existing.author, tags = tags.ifEmpty { existing.tags }, format = format,
                status = status ?: existing.status, added = System.currentTimeMillis(),
            )
            Library.put(replaced)
            return replaced
        }
        val n = LibNovel(
            id = Library.newId(), title = title, author = author, tags = tags.take(15), chapters = chapters, status = status,
            chatId = srcChat, messageIds = if (srcMsg != 0L) listOf(srcMsg) else emptyList(), fileIds = listOf(fileId),
            fileNames = listOf(fileName), format = format,
        )
        Library.add(n)
        Logger.info("Library: indexed \"$title\" ($fileName)")
        return n
    }
}
