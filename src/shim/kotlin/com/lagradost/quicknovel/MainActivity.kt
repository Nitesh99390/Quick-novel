package com.lagradost.quicknovel

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.network.CloudflareInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Server-side replacement for the Android `MainActivity`.
 *
 * Only the companion members used by the scraping engine exist here. The HTTP client is
 * configured exactly like the app (NiceHttp + Jackson, SSL errors ignored, 30s read timeout,
 * desktop user agent). `appWithInterceptor` is used by providers that declare
 * `usesCloudFlareKiller = true`; on Android it solves challenges with a WebView, here it uses
 * FlareSolverr when `FLARESOLVERR_URL` is configured (see [CloudflareInterceptor]).
 */
class MainActivity {
    companion object {
        val mapper: ObjectMapper = jacksonObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )

        val responseParser = object : ResponseParser {
            override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                mapper.readValue(text, kClass.java)

            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try {
                mapper.readValue(text, kClass.java)
            } catch (e: Exception) {
                null
            }

            override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
        }

        private fun baseClient(): OkHttpClient = OkHttpClient()
            .newBuilder()
            .ignoreAllSSLErrors()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .callTimeout(90L, TimeUnit.SECONDS)
            .build()

        var app = Requests(baseClient(), responseParser = responseParser).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }

        val appWithInterceptor by lazy {
            Requests(
                baseClient = app.baseClient.newBuilder()
                    .addInterceptor(CloudflareInterceptor())
                    .build(),
                responseParser = app.responseParser
            ).apply {
                defaultHeaders = app.defaultHeaders
            }
        }
    }
}
