package com.lagradost.quicknovel.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.Reader

object AppUtils {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        false
    )

    fun Any.toJson(): String = if (this is String) this else mapper.writeValueAsString(this)

    inline fun <reified T> parseJson(value: String): T = mapper.readValue(value)

    inline fun <reified T> parseJson(reader: Reader, valueType: Class<T>): T = mapper.readValue(reader, valueType)

    inline fun <reified T> tryParseJson(value: String?): T? = try {
        parseJson(value ?: "")
    } catch (_: Exception) {
        null
    }
}
