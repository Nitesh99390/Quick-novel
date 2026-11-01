package com.lagradost.quicknovel.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.lagradost.quicknovel.bot.Logger
import com.lagradost.quicknovel.network.utils.CookiesUtils
import com.lagradost.quicknovel.util.AppUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The Android app uses a hidden WebView to pass Cloudflare / run JS.
 * On a server we use FlareSolverr (https://github.com/FlareSolverr/FlareSolverr), a headless
 * browser proxy. Set FLARESOLVERR_URL=http://localhost:8191/v1 to enable it.
 *
 * Resource friendly:
 *  - only ONE request runs in FlareSolverr at a time (every request is a Chrome tab)
 *  - a persistent FlareSolverr *session* is reused, so Chrome is not started for every request
 *    (starting Chrome again and again is what eats the CPU / RAM of small VPS machines)
 *  - when FlareSolverr itself is down, it is not asked again for a while (circuit breaker)
 */
object FlareSolverr {
    val url: String? = (System.getenv("FLARESOLVERR_URL") ?: com.lagradost.quicknovel.bot.Config.env("FLARESOLVERR_URL"))
        ?.takeIf { it.isNotBlank() }
    val enabled get() = url != null

    private val useSession = com.lagradost.quicknovel.bot.Config.env("FLARESOLVERR_SESSION")
        ?.let { it == "1" || it.equals("true", true) } ?: true
    private const val SESSION_ID = "quicknovel-bot"

    /** User-Agent used by the solver per host – cf_clearance cookies are bound to it */
    val userAgents = ConcurrentHashMap<String, String>()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS).callTimeout(110, TimeUnit.SECONDS).build()
    }

    /** One Chrome tab at a time - FlareSolverr gets very heavy with parallel requests */
    private val gate = Semaphore(1, true)
    @Volatile private var sessionReady = false
    /** FlareSolverr itself is not reachable -> don't call it until this time */
    @Volatile private var downUntil = 0L
    @Volatile private var downCount = 0

    val isDown get() = System.currentTimeMillis() < downUntil

    data class Solution(val url: String, val status: Int, val html: String, val userAgent: String?, val cookies: Map<String, String>)

    private fun post(params: Map<String, Any?>): com.fasterxml.jackson.databind.JsonNode {
        val body = AppUtils.mapper.writeValueAsString(params.filterValues { it != null })
            .toRequestBody("application/json".toMediaType())
        return client.newCall(Request.Builder().url(url!!).post(body).build()).execute().use { res ->
            AppUtils.mapper.readTree(res.body.string())
        }
    }

    private fun ensureSession() {
        if (!useSession || sessionReady) return
        runCatching {
            val tree = post(mapOf("cmd" to "sessions.create", "session" to SESSION_ID))
            val msg = tree.path("message").asText("")
            // "already exists" is fine as well
            sessionReady = tree.path("status").asText() == "ok" || msg.contains("exist", true)
            if (sessionReady) Logger.info("FlareSolverr: using persistent session '$SESSION_ID'")
        }.onFailure { throw it }
    }

    /**
     * Loads [target] in FlareSolverr's browser. Returns null when it failed.
     * Never blocks longer than about [timeoutMs] + queue time; never runs two requests in parallel.
     */
    fun get(target: String, timeoutMs: Int = 50_000): Solution? {
        if (url == null) return null
        if (isDown) return null
        gate.acquire()
        try {
            if (isDown) return null
            ensureSession()
            var tree = post(
                mapOf(
                    "cmd" to "request.get", "url" to target, "maxTimeout" to timeoutMs,
                    "session" to if (useSession && sessionReady) SESSION_ID else null,
                    "session_ttl_minutes" to if (useSession && sessionReady) 30 else null,
                )
            )
            // the session can be gone (FlareSolverr restarted / ttl) -> recreate once and retry
            if (tree.path("status").asText() != "ok" && useSession && tree.path("message").asText("").contains("session", true)) {
                sessionReady = false
                ensureSession()
                tree = post(mapOf("cmd" to "request.get", "url" to target, "maxTimeout" to timeoutMs,
                    "session" to if (sessionReady) SESSION_ID else null))
            }
            downCount = 0
            if (tree.path("status").asText() != "ok") {
                Logger.warn("FlareSolverr could not solve $target: ${tree.path("message").asText().take(200)}")
                return null
            }
            val sol = tree.path("solution")
            val cookies = sol.path("cookies").associate { it.path("name").asText() to it.path("value").asText() }
            val ua = sol.path("userAgent").asText(null)
            val host = target.toHttpUrlOrNull()?.host
            if (host != null) {
                CookiesUtils.setCookies(host, cookies)
                if (ua != null) userAgents[host] = ua
            }
            return Solution(sol.path("url").asText(target), sol.path("status").asInt(200), sol.path("response").asText(""), ua, cookies)
        } catch (t: Throwable) {
            // FlareSolverr itself is down / overloaded: stop asking it for a while (1, 2, 4 … 15 min)
            downCount++
            val pauseMin = minOf(15L, 1L shl (downCount - 1).coerceAtMost(4))
            downUntil = System.currentTimeMillis() + pauseMin * 60_000
            sessionReady = false
            Logger.warn("FlareSolverr not reachable (${t.javaClass.simpleName}: ${t.message?.take(120)}) - paused for $pauseMin min")
            return null
        } finally {
            gate.release()
        }
    }
}
