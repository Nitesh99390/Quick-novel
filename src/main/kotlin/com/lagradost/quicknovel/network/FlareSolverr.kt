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
import java.util.concurrent.TimeUnit

/**
 * The Android app uses a hidden WebView to pass Cloudflare / run JS.
 * On a server we use FlareSolverr (https://github.com/FlareSolverr/FlareSolverr), a headless
 * browser proxy. Set FLARESOLVERR_URL=http://localhost:8191/v1 to enable it.
 */
object FlareSolverr {
    val url: String? = System.getenv("FLARESOLVERR_URL")?.takeIf { it.isNotBlank() }
    val enabled get() = url != null

    /** User-Agent used by the solver per host – cf_clearance cookies are bound to it */
    val userAgents = ConcurrentHashMap<String, String>()

    private val client by lazy {
        OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).callTimeout(130, TimeUnit.SECONDS).build()
    }

    data class Solution(val url: String, val status: Int, val html: String, val userAgent: String?, val cookies: Map<String, String>)

    fun get(target: String, timeoutMs: Int = 60_000): Solution? {
        val endpoint = url ?: return null
        return try {
            val body = AppUtils.mapper.writeValueAsString(
                mapOf("cmd" to "request.get", "url" to target, "maxTimeout" to timeoutMs)
            ).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { res ->
                val tree = AppUtils.mapper.readTree(res.body.string())
                if (tree.path("status").asText() != "ok") {
                    Logger.warn("FlareSolverr failed for $target: ${tree.path("message").asText()}")
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
                Solution(sol.path("url").asText(target), sol.path("status").asInt(200), sol.path("response").asText(""), ua, cookies)
            }
        } catch (t: Throwable) {
            Logger.warn("FlareSolverr error for $target", t)
            null
        }
    }

}
