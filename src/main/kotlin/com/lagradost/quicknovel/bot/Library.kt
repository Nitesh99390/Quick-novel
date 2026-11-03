package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.util.AppUtils
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * One novel that is stored in the dump channel.
 * The bot never uploads it again: users get it with copyMessages / the cached file_id
 * (zero CPU, zero bandwidth on our side - Telegram does the work).
 */
data class LibNovel(
    val id: String = "",
    val title: String = "",
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val chapters: Int = 0,
    val status: String? = null,
    val source: String? = null,
    val url: String? = null,
    val synopsis: String? = null,
    /** chat (dump channel) that holds the messages */
    val chatId: Long = 0,
    /** cover/info message (optional) */
    val coverMessageId: Long? = null,
    val coverFileId: String? = null,
    /** book file messages + their file ids (same order, one per volume) */
    val messageIds: List<Long> = emptyList(),
    val fileIds: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    val format: String? = null,
    val added: Long = System.currentTimeMillis(),
    var downloads: Int = 0,
)

private data class LibraryFile(val counter: Long = 0, val novels: List<LibNovel> = emptyList())

data class LibHit(val novel: LibNovel, val score: Double)

/**
 * Index of everything that is in the dump channel ("Shazam for novels").
 *
 * Everything lives in memory (a novel is ~1 KB, 10 000 novels ≈ 10 MB) and is saved to
 * `data/library.json`. Searching is a single pass over precomputed tokens/trigrams - a few
 * milliseconds even for thousands of novels, no database, no extra threads.
 */
object Library {
    private val file get() = File(Config.dataDir, "library.json")
    private val mapper = AppUtils.mapper
    private val counter = AtomicLong(0)

    /** id -> novel (insertion order = age) */
    private val novels = LinkedHashMap<String, LibNovel>()
    /** precomputed search data per id */
    private val index = HashMap<String, Indexed>()
    @Volatile private var dirty = false

    private class Indexed(
        val title: String,          // normalized title
        val words: List<String>,
        val compact: String,        // title without spaces ("shadowslave")
        val acronym: String,        // "lotm"
        val grams: Set<String>,
        val author: String,
        val tags: Set<String>,
    )

    val size get() = synchronized(this) { novels.size }

    // ------------------------------------------------------------------ persistence

    @Synchronized
    fun load() {
        val f = file
        if (!f.isFile) return
        try {
            val d: LibraryFile = mapper.readValue(f)
            counter.set(d.counter)
            d.novels.forEach { put(it) }
            Logger.info("Library: ${novels.size} novels from the dump channel indexed")
        } catch (t: Throwable) {
            Logger.error("Could not read ${f.path}", t)
            f.copyTo(File(f.path + ".broken"), overwrite = true)
        }
    }

    @Synchronized
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        dirty = false
        try {
            val tmp = File(file.path + ".tmp")
            mapper.writeValue(tmp, LibraryFile(counter.get(), novels.values.toList()))
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
        } catch (t: Throwable) {
            Logger.error("Saving library.json failed", t)
            dirty = true
        }
    }

    fun newId(): String = counter.incrementAndGet().toString(36)

    private fun put(n: LibNovel) {
        novels[n.id] = n
        index[n.id] = indexOf(n)
    }

    @Synchronized
    fun add(n: LibNovel): LibNovel {
        // the same novel again (new volumes / re-upload) replaces the old entry
        val k = norm(n.title)
        val old = novels.values.firstOrNull { norm(it.title) == k && (n.author == null || it.author == null || norm(it.author) == norm(n.author)) }
        val entry = if (old != null && old.id != n.id) {
            novels.remove(old.id); index.remove(old.id)
            n.copy(id = old.id, downloads = old.downloads)
        } else n
        put(entry)
        dirty = true
        return entry
    }

    /**
     * Adds a single book file that was posted manually in the dump channel (or forwarded to the
     * bot by an admin). Volumes of the same novel are merged into one entry.
     */
    @Synchronized
    fun addFile(chatId: Long, messageId: Long, fileId: String, fileName: String, caption: String?): LibNovel {
        val title = titleFrom(caption, fileName)
        val k = norm(title)
        val old = novels.values.firstOrNull { norm(it.title) == k }
        val n = if (old != null) {
            if (fileId in old.fileIds || (old.chatId == chatId && messageId in old.messageIds)) return old
            val sameChat = old.chatId == chatId
            old.copy(
                messageIds = if (sameChat) old.messageIds + messageId else old.messageIds,
                fileIds = old.fileIds + fileId,
                fileNames = old.fileNames + fileName,
            ).let { o -> // keep volumes ordered
                val order = o.fileNames.indices.sortedBy { volumeOf(o.fileNames[it]) }
                o.copy(fileIds = order.map { o.fileIds[it] }, fileNames = order.map { o.fileNames[it] },
                    messageIds = if (o.messageIds.size == o.fileNames.size) order.map { o.messageIds[it] } else o.messageIds)
            }
        } else {
            val meta = parseCaption(caption)
            LibNovel(
                id = newId(), title = title, author = meta["author"], tags = meta["tags"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
                chapters = meta["chapters"]?.toIntOrNull() ?: chapterCountFrom(fileName), source = meta["source"],
                chatId = chatId, messageIds = listOf(messageId), fileIds = listOf(fileId), fileNames = listOf(fileName),
                format = fileName.substringAfterLast('.', "").uppercase().ifBlank { null },
            )
        }
        put(n); dirty = true
        return n
    }

    @Synchronized fun get(id: String): LibNovel? = novels[id]

    @Synchronized
    fun remove(id: String): LibNovel? = novels.remove(id)?.also { index.remove(id); dirty = true }

    @Synchronized
    fun countDownload(id: String) {
        novels[id]?.let { it.downloads++; dirty = true }
    }

    @Synchronized fun latest(n: Int, offset: Int = 0): List<LibNovel> = novels.values.reversed().drop(offset).take(n)

    @Synchronized fun popular(n: Int): List<LibNovel> = novels.values.sortedByDescending { it.downloads }.take(n)

    @Synchronized fun random(n: Int): List<LibNovel> = novels.values.shuffled().take(n)

    @Synchronized fun contains(title: String): Boolean { val k = norm(title); return index.values.any { it.title == k } }

    /** Most used genres / tags */
    @Synchronized
    fun topTags(n: Int): List<Pair<String, Int>> = novels.values.flatMap { it.tags.map { t -> t.trim() } }
        .filter { it.length in 2..30 }.groupingBy { it.lowercase() }.eachCount()
        .entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    @Synchronized
    fun byTag(tag: String): List<LibNovel> {
        val t = norm(tag).replace(" ", "")
        return novels.values.filter { n -> n.tags.any { norm(it).replace(" ", "") == t } }.reversed()
    }

    // ------------------------------------------------------------------ search

    /**
     * Smart search over title, author and tags:
     *  - exact / prefix / phrase matches first
     *  - every word may be a prefix ("shad sla" → Shadow Slave)
     *  - typos (edit distance) and word order don't matter ("shadow slvae", "slave shadow")
     *  - acronyms ("lotm" → Lord of the Mysteries, "tbate" → The Beginning After The End)
     *  - spaces don't matter ("shadowslave")
     *  - filters: `#romance` (tag), `author:name` / `by name`, `>500` / `<100` chapters
     */
    fun search(query: String, limit: Int = 50): List<LibHit> {
        var q = query.trim()
        val tagFilters = Regex("#([\\p{L}\\p{N}_-]+)").findAll(q).map { norm(it.groupValues[1]).replace(" ", "") }.toList()
        q = q.replace(Regex("#[\\p{L}\\p{N}_-]+"), " ")
        var authorFilter: String? = null
        Regex("(?i)\\b(?:author:|by:)\\s*(.+)$").find(q)?.let { authorFilter = norm(it.groupValues[1]); q = q.substring(0, it.range.first) }
        var minCh = 0; var maxCh = Int.MAX_VALUE
        Regex("([<>])\\s*(\\d{1,5})").findAll(q).forEach { m ->
            val v = m.groupValues[2].toInt(); if (m.groupValues[1] == ">") minCh = v else maxCh = v
        }
        q = q.replace(Regex("[<>]\\s*\\d{1,5}"), " ")
        val nq = norm(q)
        val qWords = nq.split(' ').filter { it.isNotBlank() }
        val qCompact = nq.replace(" ", "")
        val qGrams = grams(nq)

        val snapshot = synchronized(this) { novels.values.map { it to index[it.id]!! } }
        val hits = ArrayList<LibHit>()
        for ((n, ix) in snapshot) {
            if (n.chapters in 1 until minCh || (maxCh != Int.MAX_VALUE && n.chapters > maxCh)) continue
            if (tagFilters.isNotEmpty() && !tagFilters.all { t -> ix.tags.any { it == t || it.startsWith(t) } }) continue
            val af = authorFilter
            if (af != null && !ix.author.contains(af) && sim(grams(af), grams(ix.author)) < 0.45) continue
            if (qWords.isEmpty()) { hits += LibHit(n, 1.0 + n.downloads * 0.01); continue }
            val s = score(nq, qWords, qCompact, qGrams, ix)
            if (s >= 28) hits += LibHit(n, s + minOf(3.0, n.downloads * 0.05))
        }
        return hits.sortedWith(compareByDescending<LibHit> { it.score }.thenByDescending { it.novel.added }).take(limit)
    }

    /** "Did you mean …" when nothing scores high enough: closest titles by trigram similarity */
    fun suggest(query: String, n: Int = 4): List<LibNovel> {
        val g = grams(norm(query))
        val snapshot = synchronized(this) { novels.values.map { it to index[it.id]!! } }
        return snapshot.map { (nv, ix) -> nv to maxOf(sim(g, ix.grams), sim(g, grams(ix.author))) }
            .filter { it.second > 0.12 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    private fun score(nq: String, qWords: List<String>, qCompact: String, qGrams: Set<String>, ix: Indexed): Double {
        val t = ix.title
        if (t == nq) return 100.0
        var best = when {
            t.startsWith(nq) -> 88.0
            ix.compact == qCompact -> 95.0
            t.contains(nq) -> 76.0
            qCompact.length >= 4 && ix.compact.contains(qCompact) -> 70.0
            else -> 0.0
        }
        // acronym: "lotm", "tbate", "mtl"
        if (qWords.size == 1 && nq.length in 2..8 && (ix.acronym == nq || (nq.length >= 3 && ix.acronym.startsWith(nq)))) {
            best = maxOf(best, if (ix.acronym == nq) 82.0 else 60.0)
        }
        // word by word: exact > prefix > typo
        var wordScore = 0.0
        for (w in qWords) {
            var b = 0.0
            for (tw in ix.words) {
                val v = when {
                    tw == w -> 1.0
                    w.length >= 2 && tw.startsWith(w) -> 0.85
                    w.length >= 4 && tw.length >= 4 && lev(w, tw, 2) <= (if (w.length >= 7) 2 else 1) -> 0.75
                    else -> 0.0
                }
                if (v > b) b = v
                if (b == 1.0) break
            }
            // tiny stop words count less
            wordScore += if (w.length <= 2 || w in STOP) b * 0.4 else b
        }
        val weight = qWords.sumOf { if (it.length <= 2 || it in STOP) 0.4 else 1.0 }.coerceAtLeast(0.4)
        val coverage = wordScore / weight
        if (coverage > 0) best = maxOf(best, 30 + 42 * coverage - 4.0 * maxOf(0, ix.words.size - qWords.size).coerceAtMost(5) / 5.0)
        // fuzzy whole-title similarity (typos + word order)
        val s = sim(qGrams, ix.grams)
        best = maxOf(best, 75 * s)
        // author & tags
        if (ix.author.isNotEmpty()) {
            if (ix.author == nq) best = maxOf(best, 66.0)
            else if (nq.length >= 4 && ix.author.contains(nq)) best = maxOf(best, 55.0)
            else if (qWords.size >= 2 && qWords.all { w -> ix.author.split(' ').any { it.startsWith(w) } }) best = maxOf(best, 50.0)
        }
        if (qWords.isNotEmpty()) {
            val tagHits = qWords.count { w -> w.length >= 3 && ix.tags.any { it.startsWith(w) } }
            if (tagHits == qWords.size) best = maxOf(best, 34.0 + 4 * tagHits)
        }
        return best
    }

    private val STOP = setOf("the", "of", "a", "an", "and", "in", "to", "my", "is", "i", "on", "for", "with", "at")

    // ------------------------------------------------------------------ text helpers

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase().replace("&", " and ").replace("'", "").replace("’", "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun grams(s: String): Set<String> {
        val p = " ${s.replace(" ", "  ")} "
        if (p.length < 3) return setOf(p)
        val out = HashSet<String>(p.length)
        for (i in 0..p.length - 3) out += p.substring(i, i + 3)
        return out
    }

    private fun sim(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        val (small, big) = if (a.size <= b.size) a to b else b to a
        for (g in small) if (g in big) inter++
        // Dice coefficient, tolerant to the query being much shorter than the title
        return maxOf(2.0 * inter / (a.size + b.size), inter.toDouble() / a.size * 0.85)
    }

    /** Levenshtein distance with early exit when it gets bigger than [max] */
    private fun lev(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
                // transposition ("slvae")
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) cur[j] = minOf(cur[j], prev[j - 1])
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun indexOf(n: LibNovel): Indexed {
        val t = norm(n.title)
        val words = t.split(' ').filter { it.isNotBlank() }
        return Indexed(
            title = t, words = words, compact = t.replace(" ", ""),
            acronym = words.joinToString("") { it.take(1) },
            grams = grams(t), author = norm(n.author),
            tags = n.tags.map { norm(it).replace(" ", "") }.filter { it.isNotBlank() }.toSet(),
        )
    }

    // ------------------------------------------------------------------ parsing manual uploads

    private fun volumeOf(fileName: String): Int =
        Regex("(?i)vol(?:ume)?\\.?\\s*(\\d+)").find(fileName)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)ch\\s*(\\d+)").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun chapterCountFrom(fileName: String): Int =
        Regex("(?i)ch\\s*(\\d+)\\s*-\\s*(\\d+)").find(fileName)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    /** Clean title from a caption ("📖 Title") or a file name ("Title (Ch 1-500) - Vol 01.epub") */
    fun titleFrom(caption: String?, fileName: String): String {
        val first = caption?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            ?.replace(Regex("^[\\p{So}\\p{Sk}\\s]+"), "")?.trim()
        if (!first.isNullOrBlank() && first.length in 2..200 && !first.startsWith("http")) return first
        return fileName.substringBeforeLast('.')
            .replace(Regex("(?i)\\s*-\\s*vol(?:ume)?\\.?\\s*\\d+.*$"), "")
            .replace(Regex("(?i)\\s*\\(ch\\s*\\d+\\s*-\\s*\\d+\\)"), "")
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { fileName }
    }

    /** Reads "✍️ author", "📚 Chapters 1–500", "🌐 Site", "#tags" from captions made by this bot */
    private fun parseCaption(caption: String?): Map<String, String> {
        if (caption.isNullOrBlank()) return emptyMap()
        val m = HashMap<String, String>()
        caption.lineSequence().forEach { l ->
            val line = l.trim()
            when {
                line.startsWith("✍") -> m["author"] = line.removePrefix("✍️").removePrefix("✍").replace("Author:", "").trim()
                line.startsWith("📚") -> Regex("(\\d+)\\D*$").find(line.substringBefore("·"))?.let { m["chapters"] = it.groupValues[1] }
                line.startsWith("🌐") -> m["source"] = line.removePrefix("🌐").substringBefore("·").replace("Source:", "").trim()
                line.contains('#') -> m["tags"] = Regex("#([\\p{L}\\p{N}_]+)").findAll(line).joinToString(" ") { it.groupValues[1] }
            }
        }
        return m
    }
}
