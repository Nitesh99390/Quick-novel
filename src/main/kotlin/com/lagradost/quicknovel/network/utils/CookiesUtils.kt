package com.lagradost.quicknovel.network.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Server replacement for android.webkit.CookieManager based CookiesUtils.
 * Cookies are stored in memory per host (filled e.g. by the FlareSolverr bypass).
 */
object CookiesUtils {
    private val store = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun parseCookieMap(cookie: String?): Map<String, String> {
        if (cookie.isNullOrBlank()) return emptyMap()
        return cookie.split(";").mapNotNull { pair ->
            val split = pair.split("=", limit = 2)
            val key = split.getOrNull(0)?.trim() ?: ""
            val value = split.getOrNull(1)?.trim() ?: ""
            if (key.isNotBlank() && value.isNotBlank()) key to value else null
        }.toMap()
    }

    fun Map<String, String>.toCookieString(): String = entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun setCookies(host: String, cookies: Map<String, String>) {
        store.getOrPut(host.removePrefix(".")) { ConcurrentHashMap() }.putAll(cookies)
    }

    fun getAllCookiesForUrl(url: String): Map<String, String> {
        val uri = url.toHttpUrlOrNull() ?: return emptyMap()
        val rootDomain = uri.topPrivateDomain() ?: uri.host
        return (store[rootDomain].orEmpty() + store[uri.host].orEmpty()).filter { it.value.isNotBlank() }
    }

    fun clearCookiesForHost(url: HttpUrl) {
        store.remove(url.host)
        url.topPrivateDomain()?.let { store.remove(it) }
    }
}
