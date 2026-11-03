package com.lagradost.quicknovel.network

import com.lagradost.quicknovel.bot.Logger
import com.lagradost.quicknovel.network.utils.CookiesUtils
import com.lagradost.quicknovel.network.utils.CookiesUtils.toCookieString
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Server version of the app's CloudflareKiller interceptor (used by providers with
 * `usesCloudFlareKiller = true`, or every provider when FlareSolverr is configured).
 *
 * Instead of an Android WebView it asks FlareSolverr for cf_clearance cookies + the matching
 * User-Agent and retries the request with them. When the cookies don't work (some sites bind
 * them to the IP/TLS fingerprint) the HTML that FlareSolverr's browser loaded is returned directly.
 *
 * Fixes the "solving challenge … FlareSolverr error" loop: earlier every single chapter request
 * started a new FlareSolverr solve, even right after one failed. That hammered FlareSolverr
 * (a Chrome per request), used all CPU / RAM and killed the SSH session. Now:
 *  - one solve per host at a time; parallel requests wait for it and reuse the cookies
 *  - a host whose solve failed is not solved again for a while (back-off 2 → 4 → 8 … 30 min);
 *    in that time the normal response is returned and the downloader's own retry / mirror logic
 *    takes over
 *  - FlareSolverr itself being down pauses it globally (see FlareSolverr.isDown)
 */
class CloudflareKiller : Interceptor {
    companion object {
        private val hostLocks = ConcurrentHashMap<String, Any>()
        /** host -> time until no new solve is attempted */
        private val coolDown = ConcurrentHashMap<String, Long>()
        private val failures = ConcurrentHashMap<String, Int>()
        /** host -> time of the last successful solve (avoid solving twice in a row) */
        private val lastSolved = ConcurrentHashMap<String, Long>()
        /** hosts where the cookies don't work -> use the browser html directly */
        private val cookiesUseless = ConcurrentHashMap.newKeySet<String>()

        fun status(): String = coolDown.filterValues { it > System.currentTimeMillis() }.keys.joinToString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host

        val saved = CookiesUtils.getAllCookiesForUrl(url)
        if (saved.containsKey("cf_clearance") && host !in cookiesUseless) {
            val res = chain.proceed(withCookies(request, saved))
            if (!looksLikeCloudflareChallenge(res)) return res
            res.close()
        } else {
            val first = chain.proceed(request)
            if (!looksLikeCloudflareChallenge(first)) return first
            // can't / shouldn't solve right now -> hand the original answer back (no new solve loop)
            if (!canSolve(host) || request.method != "GET") return first
            first.close()
        }
        if (!canSolve(host) || request.method != "GET") return chain.proceed(request)

        // one solve per host; everybody else waits and then reuses the result
        val lock = hostLocks.getOrPut(host) { Any() }
        var browserHtml: FlareSolverr.Solution? = null
        synchronized(lock) {
            val solvedRecently = (lastSolved[host] ?: 0L) > System.currentTimeMillis() - 20_000
            if (!solvedRecently || host in cookiesUseless) {
                if (!canSolve(host)) return chain.proceed(request)
                Logger.info("CloudflareKiller: solving challenge for $host via FlareSolverr")
                CookiesUtils.clearCookiesForHost(request.url)
                val sol = FlareSolverr.get(url)
                if (sol == null || sol.status >= 400 || isChallengeHtml(sol.html)) {
                    fail(host)
                    return chain.proceed(request)
                }
                lastSolved[host] = System.currentTimeMillis()
                browserHtml = sol
            }
        }

        if (host !in cookiesUseless) {
            val cookies = CookiesUtils.getAllCookiesForUrl(url)
            val res = chain.proceed(withCookies(request, cookies))
            if (!looksLikeCloudflareChallenge(res)) {
                succeed(host); return res
            }
            res.close()
            Logger.info("CloudflareKiller: cookies of $host are not accepted, using the browser html directly")
            cookiesUseless += host
        }
        // the browser itself got the page -> return it as the response
        val sol = browserHtml ?: FlareSolverr.get(url)
        if (sol != null && sol.html.isNotBlank() && sol.status < 400 && !isChallengeHtml(sol.html)) {
            succeed(host)
            return Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(sol.html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        fail(host)
        return chain.proceed(request)
    }

    private fun canSolve(host: String) =
        FlareSolverr.enabled && !FlareSolverr.isDown && (coolDown[host] ?: 0L) < System.currentTimeMillis()

    private fun fail(host: String) {
        val n = (failures[host] ?: 0) + 1
        failures[host] = n
        val minutes = minOf(30L, 1L shl n.coerceAtMost(5)) // 2, 4, 8, 16, 30 min
        coolDown[host] = System.currentTimeMillis() + minutes * 60_000
        Logger.warn("CloudflareKiller: $host could not be solved ($n×) - no new attempt for $minutes min")
    }

    private fun succeed(host: String) {
        failures.remove(host); coolDown.remove(host)
    }

    private fun withCookies(request: Request, cookies: Map<String, String>): Request {
        if (cookies.isEmpty()) return request
        val existing = request.headers.values("Cookie").joinToString("; ")
        val merged = CookiesUtils.parseCookieMap(existing) + cookies
        val builder = request.newBuilder().header("Cookie", merged.toCookieString())
        FlareSolverr.userAgents[request.url.host]?.let { builder.header("User-Agent", it) }
        return builder.build()
    }

    private fun isChallengeHtml(html: String): Boolean {
        val b = html.take(12_000).lowercase()
        return b.contains("cf-browser-verification") || b.contains("challenge-platform") && b.contains("just a moment") ||
                b.contains("<title>just a moment") || b.contains("checking your browser") || b.contains("cf-turnstile")
    }

    private fun looksLikeCloudflareChallenge(response: Response): Boolean {
        val hasCfHeaders = response.header("cf-ray") != null ||
                response.header("server")?.contains("cloudflare", ignoreCase = true) == true
        if (response.header("cf-mitigated").equals("challenge", true)) return true
        if (response.code !in listOf(403, 429, 503) && !response.header("location").orEmpty().contains("/cdn-cgi/")) {
            // a normal 200 page is never a challenge -> don't even read the body (saves CPU)
            if (response.code in 200..299) return false
        }
        val body = runCatching { response.peekBody(1024 * 10).string() }.getOrDefault("")
        val challenge = isChallengeHtml(body)
        if (response.code in listOf(403, 429, 503) && hasCfHeaders && challenge) return true
        return response.header("location").orEmpty().contains("/cdn-cgi/") || (challenge && hasCfHeaders)
    }
}
