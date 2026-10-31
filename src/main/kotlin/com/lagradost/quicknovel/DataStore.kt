package com.lagradost.quicknovel

import com.lagradost.quicknovel.util.AppUtils

object DataStore {
    val mapper get() = AppUtils.mapper

    inline fun <reified T : Any> String.toKotlinObject(): T = mapper.readValue(this, T::class.java)

    fun <T> String.toKotlinObject(valueType: Class<T>): T = mapper.readValue(this, valueType)
}
