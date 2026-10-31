package com.lagradost.quicknovel.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor used for providers with `usesCloudFlareKiller = true`
 * (server counterpart of upstream `CloudflareKiller`).
 *
 * 1. re-uses cookies/user-agent previously obtained for the host
 * 2. if the response still looks like a Cloudflare challenge and FlareSolverr is configured,
 *    solves it once and retries the original request with the fresh session.
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Internal marker header used by the app's image loader – never send it
        val request = original.newBuilder().removeHeader("useCloudflareKiller").build()
        val host = request.url.host

        val first = chain.proceed(withSession(request, host))
        if (!looksLikeChallenge(first) || !FlareSolverr.enabled) return first

        first.close()
        FlareSolverr.get(request.url.toString()) ?: return chain.proceed(withSession(request, host))
        return chain.proceed(withSession(request, host))
    }

    private fun withSession(request: okhttp3.Request, host: String): okhttp3.Request {
        val session = SessionStore.get(host) ?: return request
        val builder = request.newBuilder()
        if (session.cookies.isNotEmpty()) {
            val existing = request.header("Cookie")
                ?.split(';')
                ?.mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap().orEmpty()
            val merged = session.cookies + existing
            builder.header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        session.userAgent?.let { builder.header("User-Agent", it) }
        return builder.build()
    }

    companion object {
        fun looksLikeChallenge(response: Response): Boolean {
            if (response.code !in setOf(403, 429, 503)) return false
            val server = response.header("Server")?.lowercase().orEmpty()
            if (server.contains("cloudflare") || response.header("cf-mitigated") != null) return true
            return try {
                val peek = response.peekBody(64 * 1024).string()
                peek.contains("Just a moment", true) || peek.contains("cf-chl", true) ||
                    peek.contains("challenge-platform", true)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
