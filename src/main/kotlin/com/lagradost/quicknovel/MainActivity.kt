package com.lagradost.quicknovel

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.network.CloudflareKiller
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * JVM replacement of the app's MainActivity. The providers only use the two
 * shared NiceHttp clients from its companion object – configured exactly like upstream.
 */
class MainActivity private constructor() {
    companion object {
        val responseParser = object : ResponseParser {
            val mapper: ObjectMapper = jacksonObjectMapper().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false
            )

            override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                mapper.readValue(text, kClass.java)

            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try {
                mapper.readValue(text, kClass.java)
            } catch (e: Exception) {
                null
            }

            override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
        }

        var app = Requests(
            OkHttpClient()
                .newBuilder()
                .ignoreAllSSLErrors()
                .connectTimeout(20L, TimeUnit.SECONDS)
                .readTimeout(30L, TimeUnit.SECONDS)
                .build(),
            responseParser = responseParser
        ).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }

        val appWithInterceptor by lazy {
            Requests(
                baseClient = app.baseClient.newBuilder()
                    .addInterceptor(CloudflareKiller())
                    .build(),
                responseParser = app.responseParser
            ).apply {
                defaultHeaders = app.defaultHeaders
            }
        }
    }
}
