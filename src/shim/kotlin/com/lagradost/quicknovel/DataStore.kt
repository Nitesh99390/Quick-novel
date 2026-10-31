package com.lagradost.quicknovel

/** Only the JSON helpers of the upstream DataStore are needed by providers. */
object DataStore {
    val mapper get() = MainActivity.mapper

    inline fun <reified T : Any> String.toKotlinObject(): T = mapper.readValue(this, T::class.java)

    fun <T> String.toKotlinObject(valueType: Class<T>): T = mapper.readValue(this, valueType)
}
