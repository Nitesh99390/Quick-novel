package com.lagradost.quicknovel.bot

import java.io.File

/**
 * All runtime configuration comes from environment variables (or a `.env` file in the
 * working directory). See `.env.example`.
 */
object Config {
    private val dotEnv: Map<String, String> by lazy {
        val f = File(System.getenv("ENV_FILE") ?: ".env")
        if (!f.isFile) return@lazy emptyMap()
        f.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) return@mapNotNull null
            val key = line.substringBefore('=').trim().removePrefix("export ").trim()
            var value = line.substringAfter('=').trim()
            if (value.length >= 2 && (value.startsWith('"') && value.endsWith('"') || value.startsWith('\'') && value.endsWith('\''))) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }.toMap()
    }

    fun env(key: String): String? = (System.getenv(key) ?: dotEnv[key])?.trim()?.takeIf { it.isNotEmpty() }
    private fun int(key: String, def: Int) = env(key)?.toIntOrNull() ?: def
    private fun ids(key: String): Set<Long> =
        env(key)?.split(',', ' ', ';')?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()

    val botToken: String? get() = env("BOT_TOKEN") ?: env("TELEGRAM_BOT_TOKEN")

    /** Empty = everybody may use the bot */
    val allowedUsers: Set<Long> by lazy { ids("ALLOWED_USERS") }
    val adminIds: Set<Long> by lazy { ids("ADMIN_IDS") }

    /** Only ADMIN_IDS can use the bot (default true). false = ALLOWED_USERS / everybody */
    val adminOnly: Boolean by lazy { bool("ADMIN_ONLY", true) }

    val dataDir: File by lazy { File(env("DATA_DIR") ?: "data").apply { mkdirs() } }
    val cacheDir: File by lazy { File(dataDir, "cache").apply { mkdirs() } }

    /** Books generated at the same time (across all users) */
    val maxConcurrentDownloads: Int by lazy { int("MAX_CONCURRENT_DOWNLOADS", 2).coerceAtLeast(1) }

    /** Parallel chapter requests for providers without a rate limit */
    val chapterParallelism: Int by lazy { int("CHAPTER_PARALLELISM", 3).coerceIn(1, 16) }

    /** Max chapters per download for normal users (0 = unlimited). Admins are never limited. */
    val maxChapters: Int by lazy { int("MAX_CHAPTERS", 3000).coerceAtLeast(0) }

    /** Per provider search timeout in seconds */
    val searchTimeoutSec: Int by lazy { int("SEARCH_TIMEOUT", 25).coerceIn(5, 120) }

    /** Telegram bot API upload limit is 50 MB, keep a margin. Raise when using a local Bot API server. */
    val maxUploadBytes: Long by lazy { (env("MAX_UPLOAD_MB")?.toLongOrNull() ?: 48L) * 1024 * 1024 }

    /** Custom Bot API server, e.g. http://localhost:8081 (lets you upload up to 2 GB) */
    val apiBase: String by lazy { (env("TELEGRAM_API_URL") ?: "https://api.telegram.org").trimEnd('/') }

    /** Delete cached chapters older than this many days (0 = keep forever) */
    val cacheDays: Int by lazy { int("CACHE_DAYS", 14).coerceAtLeast(0) }

    /** Include disabled (commented out upstream) providers as well */
    val includeDisabled: Boolean by lazy { env("INCLUDE_DISABLED_PROVIDERS")?.let { it == "1" || it.equals("true", true) } ?: false }

    private fun bool(key: String, def: Boolean) = env(key)?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) || it.equals("on", true) } ?: def
    private fun list(key: String): Set<String> = env(key)?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    // ---------------------------------------------------------------- auto scraper

    /** Scrape novels by itself 24/7 and send them to the dump channel (default on) */
    val autoSend: Boolean by lazy { bool("AUTO_SEND", true) }

    /**
     * Where finished novels go: DUMP_CHANNEL_ID (e.g. -1001234567890, the bot must be admin there).
     * Falls back to AUTO_CHAT_IDS, then to ADMIN_IDS.
     */
    val autoChatIds: Set<Long> by lazy { ids("DUMP_CHANNEL_ID").ifEmpty { ids("AUTO_CHAT_IDS") } }

    /** Seconds to wait after a novel was sent before the next one starts (0 = immediately) */
    val autoDelaySec: Int by lazy { int("AUTO_DELAY_SEC", 5).coerceAtLeast(0) }

    /** Minutes to wait when no new novel was found on any site */
    val autoIdleMin: Int by lazy { (env("AUTO_IDLE_MIN") ?: env("AUTO_INTERVAL_MIN"))?.toIntOrNull()?.coerceAtLeast(1) ?: 15 }

    /** Max chapters per novel (0 = the complete novel, default) */
    val autoMaxChapters: Int by lazy { int("AUTO_MAX_CHAPTERS", 0).coerceAtLeast(0) }

    /** Novels with fewer chapters are skipped */
    val autoMinChapters: Int by lazy { int("AUTO_MIN_CHAPTERS", 5).coerceAtLeast(1) }

    /** Novels with more chapters are skipped (0 = no limit) */
    val autoSkipAbove: Int by lazy { int("AUTO_SKIP_ABOVE", 0).coerceAtLeast(0) }

    val autoFormat: String by lazy { env("AUTO_FORMAT") ?: "EPUB" }

    /** Only these languages (e.g. "en"). Empty = all */
    val autoLanguages: Set<String> by lazy { list("AUTO_LANGUAGES").ifEmpty { setOf("en") } }

    /** How deep the popular / latest lists of a site are searched for a not-yet-sent novel */
    val autoMaxPages: Int by lazy { int("AUTO_MAX_PAGES", 100).coerceIn(1, 1000) }

    /** Only these sites (provider names). Empty = every site of SITES */
    val autoSites: Set<String> by lazy { list("AUTO_SITES") }

    /** Max share of chapters that may fail (percent) - worse books are not sent */
    val autoMaxFailedPercent: Int by lazy { int("AUTO_MAX_FAILED_PERCENT", 10).coerceIn(0, 100) }
}
