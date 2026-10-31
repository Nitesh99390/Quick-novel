package com.lagradost.quicknovel.network

import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.bot.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Server replacement for the WebView based resolver of the app.
 *
 * Upstream providers call `WebViewResolver(scriptToFinish = script).resolveUsingWebView(url)` with a
 * small JS snippet that waits for `document.querySelector("<selector>")` and returns its
 * innerHTML / outerHTML / innerText via `NativeAndroid.onElementFound(...)`.
 * We load the page through FlareSolverr (real browser) or plain HTTP, then evaluate the same
 * selector with Jsoup and return the same property – so the provider code stays unmodified.
 */
class WebViewResolver(
    val interceptUrl: Regex? = null,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = null,
    val useOkhttp: Boolean = false,
    val scriptToFinish: String? = null
) {
    companion object {
        /** No WebView on the server: the user agent of the last FlareSolverr solution is used instead */
        @get:JvmName("webViewUserAgentValue")
        val webViewUserAgent: String? get() = FlareSolverr.userAgents.values.firstOrNull()
        fun getWebViewUserAgent(): String? = webViewUserAgent
    }

    private data class Extraction(val selector: String, val nested: String?, val property: String)

    private fun parseScript(script: String): Extraction? {
        val selectors = Regex("""querySelector\(\s*["'](.+?)["']\s*\)""").findAll(script).map { it.groupValues[1] }.toList()
        if (selectors.isEmpty()) return null
        val found = Regex("""onElementFound\(\s*([A-Za-z_]+)\.(innerHTML|outerHTML|innerText|textContent)""").find(script)
        val variable = found?.groupValues?.get(1)
        val property = found?.groupValues?.get(2) ?: "innerHTML"
        // The first selector is the element that is returned (others are only used to wait)
        val mainSelector = variable?.let { v ->
            Regex("""var\s+$v\s*=\s*document\.querySelector\(\s*["'](.+?)["']""").find(script)?.groupValues?.get(1)
        } ?: selectors.first()
        return Extraction(mainSelector, null, property)
    }

    private fun extract(doc: Document, ex: Extraction): String? {
        val el = doc.selectFirst(ex.selector) ?: return null
        return when (ex.property) {
            "outerHTML" -> el.outerHtml()
            "innerText", "textContent" -> el.wholeText().trim().ifBlank { el.text() }
            else -> el.html()
        }.takeIf { it.isNotBlank() }
    }

    suspend fun resolveUsingWebView(url: String, referer: String? = null, method: String = "GET"): String? =
        withContext(Dispatchers.IO) {
            val ex = scriptToFinish?.let { parseScript(it) }
            val html: String? = FlareSolverr.get(url)?.html ?: runCatching {
                app.get(url, referer = referer).text
            }.getOrNull()
            if (html == null) {
                Logger.warn("WebViewResolver: could not load $url")
                return@withContext null
            }
            if (ex == null) return@withContext html
            val result = extract(Jsoup.parse(html, url), ex)
            if (result == null) {
                Logger.warn(
                    "WebViewResolver: element '${ex.selector}' not found on $url" +
                            if (!FlareSolverr.enabled) " (set FLARESOLVERR_URL for JS/Cloudflare sites)" else ""
                )
            }
            result
        }
}
