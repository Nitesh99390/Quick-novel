package com.lagradost.quicknovel.network

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Server replacement for the Android WebView based Cloudflare solving.
 *
 * If the environment variable `FLARESOLVERR_URL` (e.g. `http://localhost:8191`) is set, challenge
 * pages are solved through a FlareSolverr instance (headless Chrome). The resulting cookies +
 * user-agent are cached per host in [SessionStore] and reused by every subsequent request,
 * exactly like the app reuses WebView cookies.
 */
object FlareSolverr {
    private val log = LoggerFactory.getLogger("FlareSolverr")

    val endpoint: String? = System.getenv("FLARESOLVERR_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val enabled: Boolean get() = endpoint != null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()

    data class Cookie(@JsonProperty("name") val name: String, @JsonProperty("value") val value: String)
    data class Solution(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("cookies") val cookies: List<Cookie>? = null,
        @JsonProperty("userAgent") val userAgent: String? = null,
        @JsonProperty("response") val response: String? = null,
    )

    data class Result(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("solution") val solution: Solution? = null,
    )

    /** Fetches [url] through FlareSolverr, stores cookies and returns the rendered HTML. */
    fun get(url: String, timeoutMs: Int = 60_000): Solution? {
        val ep = endpoint ?: return null
        return try {
            val body = MainActivity.mapper.writeValueAsString(
                mapOf("cmd" to "request.get", "url" to url, "maxTimeout" to timeoutMs)
            ).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$ep/v1").post(body).build()).execute().use { res ->
                val parsed = MainActivity.mapper.readValue(res.body.string(), Result::class.java)
                if (parsed.status != "ok") {
                    log.warn("FlareSolverr failed for {}: {}", url, parsed.message)
                    return null
                }
                parsed.solution?.also { sol ->
                    val host = url.toHttpUrlOrNull()?.host ?: return@also
                    SessionStore.put(
                        host,
                        sol.cookies.orEmpty().associate { it.name to it.value },
                        sol.userAgent
                    )
                }
            }
        } catch (t: Throwable) {
            log.warn("FlareSolverr error for {}: {}", url, t.toString())
            null
        }
    }
}

/** Cookies / user agents obtained from challenge solving, keyed by host. */
object SessionStore {
    data class Session(val cookies: Map<String, String>, val userAgent: String?)

    private val sessions = ConcurrentHashMap<String, Session>()

    fun put(host: String, cookies: Map<String, String>, userAgent: String?) {
        val old = sessions[host]
        sessions[host] = Session((old?.cookies ?: emptyMap()) + cookies, userAgent ?: old?.userAgent)
    }

    /** Matches the exact host and any parent domain (cookies set on `.example.com`). */
    fun get(host: String): Session? {
        var h = host
        while (true) {
            sessions[h]?.let { return it }
            val idx = h.indexOf('.')
            if (idx < 0 || h.indexOf('.', idx + 1) < 0) return null
            h = h.substring(idx + 1)
        }
    }

    fun clear(host: String) {
        sessions.remove(host)
    }
}
