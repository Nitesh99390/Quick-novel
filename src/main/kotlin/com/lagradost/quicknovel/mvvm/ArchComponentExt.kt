package com.lagradost.quicknovel.mvvm

import com.lagradost.quicknovel.bot.Logger

fun logError(throwable: Throwable) {
    Logger.debug("ApiError: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
}

fun <T> safe(apiCall: () -> T): T? = try {
    apiCall.invoke()
} catch (throwable: Throwable) {
    logError(throwable)
    null
}

suspend fun <T> safeAsync(apiCall: suspend () -> T): T? = try {
    apiCall.invoke()
} catch (throwable: Throwable) {
    logError(throwable)
    null
}
