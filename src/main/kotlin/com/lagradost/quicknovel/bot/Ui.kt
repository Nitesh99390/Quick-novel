package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.EpubResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse

/** Text formatting helpers for Telegram HTML messages */
object Ui {
    fun h(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun number(n: Int?): String = when {
        n == null -> "?"
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    fun bar(done: Int, total: Int, width: Int = 14): String {
        val f = if (total <= 0) 0 else (done.toLong() * width / total).toInt().coerceIn(0, width)
        return "▰".repeat(f) + "▱".repeat(width - f)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    fun flag(lang: String): String = when (lang.lowercase()) {
        "en" -> "🇬🇧"; "es" -> "🇪🇸"; "fr" -> "🇫🇷"; "de" -> "🇩🇪"; "ru" -> "🇷🇺"; "ar" -> "🇸🇦"
        "id" -> "🇮🇩"; "pt", "pt-br" -> "🇧🇷"; "zh" -> "🇨🇳"; "ja" -> "🇯🇵"; "ko" -> "🇰🇷"; "vi" -> "🇻🇳"
        "tr" -> "🇹🇷"; "it" -> "🇮🇹"; "th" -> "🇹🇭"; "hi" -> "🇮🇳"; "uk" -> "🇺🇦"; "pl" -> "🇵🇱"
        else -> "🌐"
    }

    fun chapterCount(r: LoadResponse): Int = (r as? StreamResponse)?.data?.size ?: 0

    fun novelCard(r: LoadResponse, lang: String, maxLen: Int): String {
        val sb = StringBuilder()
        sb.append("📖 <b>").append(h(r.name)).append("</b>\n")
        r.author?.takeIf { it.isNotBlank() }?.let { sb.append("✍️ ").append(h(it.trim())).append('\n') }
        sb.append(flag(lang)).append(' ').append(h(r.apiName)).append('\n')
        val line = mutableListOf<String>()
        r.status?.let { line += "🔖 ${it.name}" }
        r.rating?.let { line += "⭐ %.1f/10".format(it / 100.0) }
        r.peopleVoted?.let { line += "🗳 ${number(it)}" }
        r.views?.let { line += "👁 ${number(it)}" }
        if (line.isNotEmpty()) sb.append(line.joinToString("  ")).append('\n')
        when (r) {
            is StreamResponse -> {
                sb.append("📚 <b>").append(r.data.size).append("</b> chapters")
                r.data.lastOrNull()?.let { sb.append("\n🆕 ").append(h(cut(it.name, 70))) }
                sb.append('\n')
            }
            is EpubResponse -> sb.append("📦 Complete book file (").append(r.downloadLinks.size + r.downloadExtractLinks.size).append(" links)\n")
        }
        r.tags?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
            sb.append("🏷 <i>").append(h(cut(it.take(10).joinToString(" · "), 200))).append("</i>\n")
        }
        val head = sb.toString()
        val syn = r.synopsis?.replace(Regex("\n{3,}"), "\n\n")?.trim()
        if (!syn.isNullOrBlank()) {
            val room = maxLen - head.length - 40
            if (room > 80) {
                return head + "\n<blockquote expandable>" + h(cut(syn, room)) + "</blockquote>"
            }
        }
        return head
    }

    fun resultLine(i: Int, s: SearchResponse): String {
        val extra = listOfNotNull(
            s.latestChapter?.takeIf { it.isNotBlank() }?.let { cut(it.trim(), 40) },
            s.rating?.let { "⭐%.1f".format(it / 100.0) },
        ).joinToString(" · ")
        return "<b>$i.</b> ${h(cut(s.name, 70))}\n     <i>${h(s.apiName)}</i>" + (if (extra.isNotEmpty()) " · ${h(extra)}" else "")
    }
}
