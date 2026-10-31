package com.lagradost.quicknovel.network

import com.lagradost.quicknovel.bot.Logger
import com.lagradost.quicknovel.network.utils.CookiesUtils
import com.lagradost.quicknovel.network.utils.CookiesUtils.toCookieString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Server version of the app's CloudflareKiller interceptor (used by providers with
 * `usesCloudFlareKiller = true`). Instead of an Android WebView it asks FlareSolverr for
 * cf_clearance cookies + the matching User-Agent and retries the request with them.
 */
class CloudflareKiller : Interceptor {
    companion object {
        private val mutex = Mutex()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host

        val saved = CookiesUtils.getAllCookiesForUrl(url)
        if (saved.containsKey("cf_clearance")) {
            val res = chain.proceed(withCookies(request, saved))
            if (!looksLikeCloudflareChallenge(res)) return res
            res.close()
            CookiesUtils.clearCookiesForHost(request.url)
        }

        val first = chain.proceed(request)
        if (!looksLikeCloudflareChallenge(first) || !FlareSolverr.enabled) return first
        first.close()

        runBlocking {
            mutex.withLock {
                if (!CookiesUtils.getAllCookiesForUrl(url).containsKey("cf_clearance")) {
                    Logger.info("CloudflareKiller: solving challenge for $host via FlareSolverr")
                    FlareSolverr.get(url)
                }
            }
        }
        val cookies = CookiesUtils.getAllCookiesForUrl(url)
        return chain.proceed(withCookies(request, cookies))
    }

    private fun withCookies(request: Request, cookies: Map<String, String>): Request {
        if (cookies.isEmpty()) return request
        val existing = request.headers.values("Cookie").joinToString("; ")
        val merged = CookiesUtils.parseCookieMap(existing) + cookies
        val builder = request.newBuilder().header("Cookie", merged.toCookieString())
        FlareSolverr.userAgents[request.url.host]?.let { builder.header("User-Agent", it) }
        return builder.build()
    }

    private fun looksLikeCloudflareChallenge(response: Response): Boolean {
        val hasCfHeaders = response.header("cf-ray") != null ||
                response.header("server")?.contains("cloudflare", ignoreCase = true) == true
        val body = runCatching { response.peekBody(1024 * 10).string().lowercase() }.getOrDefault("")
        val challenge = body.contains("cf-browser-verification") || body.contains("checking your browser") ||
                body.contains("just a moment") || body.contains("challenge-platform")
        if (response.code in listOf(403, 429, 503) && (hasCfHeaders || challenge)) return true
        return response.header("location").orEmpty().contains("/cdn-cgi/") || (challenge && hasCfHeaders)
    }
}
