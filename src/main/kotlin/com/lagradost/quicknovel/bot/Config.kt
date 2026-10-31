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
}
