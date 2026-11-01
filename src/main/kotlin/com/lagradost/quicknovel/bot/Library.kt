package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.util.AppUtils
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * One complete novel that is stored in the dump channel.
 * The files are never uploaded again: they are copied from [chatId]/[messageIds]
 * (or re-sent by [fileIds] when the original message is not reachable).
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
    /** chat that holds the original messages (0 = only file ids are known) */
    val chatId: Long = 0,
    val coverMessageId: Long? = null,
    val coverFileId: String? = null,
    val messageIds: List<Long> = emptyList(),
    val fileIds: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    val format: String? = null,
    val added: Long = System.currentTimeMillis(),
    var downloads: Int = 0,
)

data class LibHit(val novel: LibNovel, val score: Double)

private data class LibraryFile(val novels: List<LibNovel> = emptyList())

/**
 * "Shazam for novels": every book in the dump channel, searchable with typos, short forms
 * ("lotm" = Lord of the Mysteries), partial names, author names and tags.
 * Stored in data/library.json. Search is in-memory and takes well below a millisecond per 1000 novels.
 */
object Library {
    private val file get() = File(Config.dataDir, "library.json")
    private val mapper = AppUtils.mapper
    private val novels = ConcurrentHashMap<String, LibNovel>()
    @Volatile private var dirty = false

    // ------------------------------------------------------------------ search index

    private class Entry(
        val novel: LibNovel,
        val title: String,          // normalized
        val compact: String,        // normalized without spaces
        val tokens: List<String>,
        val initials: String,       // "lord of the mysteries" -> "lotm"
        val initialsNoStop: String, // -> "lm"
        val author: String,
        val tags: Set<String>,
        val grams: Set<String>,
    )

    @Volatile private var index: List<Entry>? = null

    private val stopWords = setOf("of", "the", "a", "an", "and", "in", "to", "for", "with", "on", "my", "is")

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.lowercase().replace("'", "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun grams(s: String): Set<String> {
        val p = " $s "
        if (p.length < 3) return setOf(p)
        return (0..p.length - 3).map { p.substring(it, it + 3) }.toSet()
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var common = 0
        val (small, big) = if (a.size < b.size) a to b else b to a
        for (g in small) if (g in big) common++
        return 2.0 * common / (a.size + b.size)
    }

    /** Levenshtein distance, aborts early when it exceeds [limit] */
    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun entry(n: LibNovel): Entry {
        val title = norm(n.title)
        val tokens = title.split(' ').filter { it.isNotEmpty() }
        val compact = title.replace(" ", "")
        return Entry(
            n, title, compact, tokens,
            tokens.joinToString("") { it.take(1) },
            tokens.filter { it !in stopWords }.joinToString("") { it.take(1) },
            norm(n.author),
            n.tags.map { norm(it) }.filter { it.isNotEmpty() }.toSet(),
            grams(compact),
        )
    }

    private fun entries(): List<Entry> = index ?: synchronized(this) {
        index ?: novels.values.map { entry(it) }.also { index = it }
    }

    private fun tokenScore(q: String, t: String): Double = when {
        q == t -> 1.0
        q.length >= 2 && t.startsWith(q) -> 0.85
        q.length >= 4 && distance(q, t, 1) <= 1 -> 0.75
        q.length >= 7 && distance(q, t, 2) <= 2 -> 0.6
        q.length >= 4 && t.length > q.length && distance(q, t.take(q.length), 1) <= 1 -> 0.55 // typo in a prefix
        else -> 0.0
    }

    private fun score(e: Entry, q: String, qc: String, qTokens: List<String>, qGrams: Set<String>): Double {
        if (qc.isEmpty()) return 0.0
        var best = 0.0
        if (qc == e.compact) return 100.0
        if (e.title.startsWith(q)) best = max(best, 90.0 - min(10.0, (e.title.length - q.length) / 4.0))
        else if (q.length >= 3 && e.title.contains(q)) best = max(best, 78.0)
        if (qc.length >= 2 && !qc.contains(' ')) {
            if (qc == e.initials || (e.initialsNoStop.length >= 2 && qc == e.initialsNoStop)) best = max(best, 88.0)
        }
        // every query word against the title words (typos and prefixes allowed)
        if (qTokens.isNotEmpty() && e.tokens.isNotEmpty()) {
            var sum = 0.0
            var matched = 0
            for (qt in qTokens) {
                val s = e.tokens.maxOf { tokenScore(qt, it) }
                if (s > 0) matched++
                sum += if (s == 0.0 && qt in stopWords) 0.5 else s
            }
            val avg = sum / qTokens.size
            val coverage = matched.toDouble() / e.tokens.size.coerceAtLeast(1)
            if (matched > 0) best = max(best, 72.0 * avg + 8.0 * min(1.0, coverage))
        }
        // whole string similarity (catches "shadowslave", "reverend insanty", missing spaces …)
        val d = dice(qGrams, e.grams)
        if (d >= 0.35) best = max(best, 75.0 * d)
        if (e.author.isNotEmpty() && q.length >= 3) {
            if (e.author == q) best = max(best, 70.0)
            else if (e.author.contains(q)) best = max(best, 55.0)
        }
        if (q in e.tags) best = max(best, 40.0)
        return best
    }

    /** Best matching novels, best first. Typos, short forms, author names and tags work. */
    fun search(query: String, limit: Int = 30, minScore: Double = 38.0): List<LibHit> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val qc = q.replace(" ", "")
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val qGrams = grams(qc)
        return entries().asSequence()
            .map { LibHit(it.novel, score(it, q, qc, qTokens, qGrams)) }
            .filter { it.score >= minScore }
            .sortedWith(compareByDescending<LibHit> { it.score }.thenByDescending { it.novel.downloads }.thenByDescending { it.novel.chapters })
            .take(limit).toList()
    }

    /** "Did you mean …" when nothing matched */
    fun suggest(query: String, n: Int = 3): List<LibNovel> {
        val q = norm(query).replace(" ", "")
        if (q.isEmpty()) return emptyList()
        val g = grams(q)
        return entries().map { it to dice(g, it.grams) }.filter { it.second >= 0.18 }
            .sortedByDescending { it.second }.take(n).map { it.first.novel }
    }

    // ------------------------------------------------------------------ access

    val size get() = novels.size

    fun get(id: String): LibNovel? = novels[id]

    fun all(): List<LibNovel> = novels.values.toList()

    fun newest(): List<LibNovel> = novels.values.sortedByDescending { it.added }

    fun popular(): List<LibNovel> = novels.values.sortedWith(compareByDescending<LibNovel> { it.downloads }.thenByDescending { it.added })

    fun random(): LibNovel? = novels.values.randomOrNull()

    /** genre -> number of novels, most common first */
    fun genres(): List<Pair<String, Int>> {
        val counts = HashMap<String, Pair<String, Int>>()
        for (n in novels.values) for (t in n.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { norm(it) }) {
            val k = norm(t)
            if (k.isEmpty()) continue
            val old = counts[k]
            counts[k] = (old?.first ?: t) to ((old?.second ?: 0) + 1)
        }
        return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }

    fun byGenre(genre: String): List<LibNovel> {
        val g = norm(genre)
        return novels.values.filter { n -> n.tags.any { norm(it) == g } }
            .sortedWith(compareByDescending<LibNovel> { it.downloads }.thenByDescending { it.added })
    }

    fun findByTitle(title: String): LibNovel? {
        val k = norm(title).replace(" ", "")
        if (k.isEmpty()) return null
        return novels.values.firstOrNull { norm(it.title).replace(" ", "") == k }
    }

    fun newId(): String {
        val chars = "abcdefghijkmnopqrstuvwxyz23456789"
        while (true) {
            val id = (1..6).map { chars.random() }.joinToString("")
            if (!novels.containsKey(id)) return id
        }
    }

    /** Adds a novel; a novel with the same title is replaced (newer / more chapters wins) */
    fun add(n: LibNovel) {
        val old = findByTitle(n.title)
        if (old != null && old.id != n.id) {
            novels.remove(old.id)
            novels[old.id] = n.copy(id = old.id, downloads = old.downloads + n.downloads)
        } else {
            novels[n.id] = n
        }
        changed()
    }

    fun put(n: LibNovel) { novels[n.id] = n; changed() }

    fun remove(id: String): LibNovel? = novels.remove(id)?.also { changed() }

    fun countDownload(id: String) {
        novels[id]?.let { it.downloads++; dirty = true }
    }

    private fun changed() { index = null; dirty = true }

    // ------------------------------------------------------------------ persistence

    fun load() {
        val f = file
        if (!f.isFile) return
        try {
            val data: LibraryFile = mapper.readValue(f)
            data.novels.filter { it.id.isNotBlank() && it.title.isNotBlank() }.forEach { novels[it.id] = it }
            index = null
            Logger.info("Library: ${novels.size} novels loaded")
        } catch (t: Throwable) {
            Logger.error("Could not read ${f.path}, starting with an empty library", t)
            runCatching { f.copyTo(File(f.path + ".broken"), overwrite = true) }
        }
    }

    @Synchronized
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        dirty = false
        try {
            val tmp = File(file.path + ".tmp")
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, LibraryFile(novels.values.sortedBy { it.added }))
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
        } catch (t: Throwable) {
            Logger.error("Saving library failed", t)
            dirty = true
        }
    }
}
