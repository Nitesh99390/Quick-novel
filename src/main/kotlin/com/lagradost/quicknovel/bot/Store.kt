package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.util.AppUtils
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class UserSettings(
    var format: String = "EPUB",
    /** empty = all languages */
    var languages: MutableSet<String> = mutableSetOf(),
    /** providers the user disabled */
    var disabledProviders: MutableSet<String> = mutableSetOf(),
    var stripAuthorNotes: Boolean = true,
    var sendCover: Boolean = true,
)

data class HistoryItem(val ref: String, val title: String, val api: String, val time: Long)

data class UserData(
    var id: Long = 0,
    var name: String? = null,
    var username: String? = null,
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var settings: UserSettings = UserSettings(),
    var history: MutableList<HistoryItem> = mutableListOf(),
    var library: MutableList<HistoryItem> = mutableListOf(),
    var searches: Int = 0,
    var downloads: Int = 0,
    var banned: Boolean = false,
)

data class NovelRef(val api: String, val url: String, val title: String? = null)

data class Stats(var searches: Long = 0, var downloads: Long = 0, var chapters: Long = 0, var bytesSent: Long = 0)

private data class StoreFile(
    val users: Map<Long, UserData> = emptyMap(),
    val refs: Map<String, NovelRef> = emptyMap(),
    val stats: Stats = Stats(),
)

/** Tiny JSON file database (data/store.json), saved asynchronously after changes. */
object Store {
    private val file get() = File(Config.dataDir, "store.json")
    private val mapper = AppUtils.mapper

    val users = ConcurrentHashMap<Long, UserData>()
    private val refs = ConcurrentHashMap<String, NovelRef>()
    var stats = Stats(); private set

    @Volatile private var dirty = false

    fun load() {
        val f = file
        if (!f.isFile) return
        try {
            val data: StoreFile = mapper.readValue(f)
            users.putAll(data.users)
            refs.putAll(data.refs)
            stats = data.stats
            Logger.info("Store: ${users.size} users, ${refs.size} novel refs loaded")
        } catch (t: Throwable) {
            Logger.error("Could not read ${f.path}, starting empty", t)
            f.copyTo(File(f.path + ".broken"), overwrite = true)
        }
    }

    fun markDirty() { dirty = true }

    @Synchronized
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        dirty = false
        try {
            // keep the reference table bounded
            if (refs.size > 20000) {
                val keep = users.values.flatMap { it.history + it.library }.map { it.ref }.toSet()
                refs.keys.filter { it !in keep }.take(refs.size - 15000).forEach { refs.remove(it) }
            }
            val tmp = File(file.path + ".tmp")
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, StoreFile(HashMap(users), HashMap(refs), stats))
            tmp.renameTo(file).also { if (!it) { tmp.copyTo(file, true); tmp.delete() } }
        } catch (t: Throwable) {
            Logger.error("Saving store failed", t)
            dirty = true
        }
    }

    fun user(id: Long, name: String? = null, username: String? = null): UserData {
        val u = users.getOrPut(id) { UserData(id = id).also { markDirty() } }
        if (name != null && u.name != name) { u.name = name; markDirty() }
        if (username != null && u.username != username) { u.username = username; markDirty() }
        u.lastSeen = System.currentTimeMillis()
        return u
    }

    /** Short stable id for a novel (callback_data is limited to 64 bytes) */
    fun ref(api: String, url: String, title: String? = null): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$api|$url".toByteArray())
        val id = digest.take(6).joinToString("") { "%02x".format(it) }
        val existing = refs[id]
        if (existing == null || (title != null && existing.title != title)) {
            refs[id] = NovelRef(api, url, title ?: existing?.title); markDirty()
        }
        return id
    }

    fun resolve(id: String): NovelRef? = refs[id]

    fun addHistory(user: UserData, ref: String, title: String, api: String) {
        user.history.removeAll { it.ref == ref }
        user.history.add(0, HistoryItem(ref, title, api, System.currentTimeMillis()))
        while (user.history.size > 30) user.history.removeAt(user.history.size - 1)
        markDirty()
    }

    /** returns true when added, false when removed */
    fun toggleLibrary(user: UserData, ref: String, title: String, api: String): Boolean {
        val removed = user.library.removeAll { it.ref == ref }
        if (!removed) user.library.add(0, HistoryItem(ref, title, api, System.currentTimeMillis()))
        markDirty()
        return !removed
    }

    fun inLibrary(user: UserData, ref: String) = user.library.any { it.ref == ref }
}
