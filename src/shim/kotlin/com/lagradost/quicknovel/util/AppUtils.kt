package com.lagradost.quicknovel.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.MainActivity
import java.io.Reader

/** JSON helpers from the upstream AppUtils (identical mapper configuration). */
object AppUtils {
    val mapper: ObjectMapper get() = MainActivity.mapper

    fun Any.toJson(): String {
        if (this is String) return this
        return mapper.writeValueAsString(this)
    }

    inline fun <reified T> parseJson(value: String): T = mapper.readValue(value)

    inline fun <reified T> parseJson(reader: Reader, valueType: Class<T>): T = mapper.readValue(reader, valueType)

    inline fun <reified T> tryParseJson(value: String?): T? = try {
        parseJson(value ?: return null)
    } catch (_: Exception) {
        null
    }
}
