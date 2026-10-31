package com.lagradost.quicknovel.bot

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tiny dependency-free logger. LOG_LEVEL=debug|info|warn|error */
object Logger {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val level: Int = when (System.getenv("LOG_LEVEL")?.lowercase()) {
        "debug" -> 0; "warn" -> 2; "error" -> 3; else -> 1
    }

    private fun log(l: Int, tag: String, msg: String, t: Throwable? = null) {
        if (l < level) return
        val line = "${LocalDateTime.now().format(fmt)} $tag $msg"
        if (l >= 2) System.err.println(line) else println(line)
        if (t != null && level == 0) t.printStackTrace()
    }

    fun debug(msg: String, t: Throwable? = null) = log(0, "DEBUG", msg, t)
    fun info(msg: String) = log(1, "INFO ", msg)
    fun warn(msg: String, t: Throwable? = null) = log(2, "WARN ", msg, t)
    fun error(msg: String, t: Throwable? = null) = log(3, "ERROR", msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""), t)
}
