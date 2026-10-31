package com.lagradost.quicknovel.mvvm

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("QuickNovel")

/** When true (env QN_DEBUG=1) full stack traces of provider errors are printed. */
val debugErrors: Boolean = System.getenv("QN_DEBUG")?.let { it == "1" || it.equals("true", true) } ?: false

fun logError(throwable: Throwable) {
    if (debugErrors) log.warn("ApiError: ${throwable.message}", throwable)
    else log.debug("ApiError: {}", throwable.toString())
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

inline fun debugWarning(message: () -> String) {
    if (debugErrors) LoggerFactory.getLogger("QuickNovel").warn(message())
}
