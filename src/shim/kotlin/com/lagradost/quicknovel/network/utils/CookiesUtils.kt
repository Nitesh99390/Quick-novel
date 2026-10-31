package com.lagradost.quicknovel.network.utils

import com.lagradost.quicknovel.network.SessionStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Server version of the upstream CookiesUtils backed by [SessionStore] instead of CookieManager. */
object CookiesUtils {
    fun parseCookieMap(cookie: String?): Map<String, String> {
        if (cookie.isNullOrBlank()) return emptyMap()
        return cookie.split(";").mapNotNull { pair ->
            val split = pair.split("=", limit = 2)
            val key = split.getOrNull(0)?.trim() ?: ""
            val value = split.getOrNull(1)?.trim() ?: ""
            if (key.isNotBlank() && value.isNotBlank()) key to value else null
        }.toMap()
    }

    fun Map<String, String>.toCookieString(): String =
        this.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun getAllCookiesForUrl(url: String): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
        return SessionStore.get(host)?.cookies.orEmpty()
    }

    fun clearCookiesForHost(url: HttpUrl) {
        SessionStore.clear(url.host)
    }
}
