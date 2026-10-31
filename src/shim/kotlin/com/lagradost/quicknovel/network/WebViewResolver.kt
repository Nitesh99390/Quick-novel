package com.lagradost.quicknovel.network

import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.USER_AGENT
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

/**
 * Server stand-in for the Android WebView resolver.
 *
 * Upstream providers call `WebViewResolver(scriptToFinish = js).resolveUsingWebView(url)` where
 * the JS waits for `document.querySelector("<selector>")` and reports `innerHTML`, `outerHTML`
 * or `innerText` via `NativeAndroid.onElementFound(...)`.
 *
 * Here the page is rendered by FlareSolverr (if configured, otherwise fetched directly) and the
 * same selector/property is extracted from the script and evaluated with Jsoup – so providers
 * keep working unchanged on a server for any page that does not need client side decryption.
 */
class WebViewResolver(
    val interceptUrl: Regex? = null,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = null,
    val useOkhttp: Boolean = false,
    val scriptToFinish: String? = null,
) {
    private val log = LoggerFactory.getLogger("WebViewResolver")

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
    ): String? {
        val html = fetchRendered(url, referer) ?: return null
        val script = scriptToFinish ?: return html
        return extract(html, script)
    }

    private suspend fun fetchRendered(url: String, referer: String?): String? {
        FlareSolverr.get(url)?.response?.let { return it }
        return try {
            val host = url.toHttpUrlOrNull()?.host
            val session = host?.let { SessionStore.get(it) }
            val headers = buildMap {
                put("User-Agent", session?.userAgent ?: userAgent ?: USER_AGENT)
            }
            MainActivity.app.get(
                url,
                headers = headers,
                referer = referer,
                cookies = session?.cookies ?: emptyMap()
            ).text
        } catch (t: Throwable) {
            log.debug("direct fetch failed for {}: {}", url, t.toString())
            null
        }
    }

    companion object {
        private val selectorRegex = Regex("""querySelector\(\s*(["'])(.+?)\1\s*\)""")
        private val resultRegex = Regex("""onElementFound\(\s*([A-Za-z_$][\w$]*)\.(innerHTML|outerHTML|innerText|textContent)""")

        /** Evaluates the subset of the upstream "wait for element" scripts with Jsoup. */
        fun extract(html: String, script: String): String? {
            val doc = Jsoup.parse(html)
            val result = resultRegex.find(script)
            val property = result?.groupValues?.get(2) ?: "innerHTML"
            val variable = result?.groupValues?.get(1)

            // pick the querySelector assigned to the variable that is reported, default first
            val selectors = selectorRegex.findAll(script).map { it.groupValues[2] }.toList()
            if (selectors.isEmpty()) return html
            val assigned = variable?.let { v ->
                Regex("""\b$v\s*=\s*[^;]*?querySelector\(\s*(["'])(.+?)\1""").find(script)?.groupValues?.get(2)
            }
            val selector = assigned ?: selectors.first()
            val element = doc.selectFirst(selector) ?: return null
            return when (property) {
                "outerHTML" -> element.outerHtml()
                "innerText", "textContent" -> element.text().trim()
                else -> element.html()
            }.takeIf { it.isNotBlank() }
        }
    }
}
