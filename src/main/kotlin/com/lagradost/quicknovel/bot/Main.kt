package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.StreamResponse
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private fun usage() {
    println(
        """
QuickNovel Telegram Bot

Usage:
  quicknovel-bot                         start the Telegram bot (needs BOT_TOKEN)
  quicknovel-bot providers               list providers
  quicknovel-bot search <query>          search all providers
  quicknovel-bot info <url>              load a novel page
  quicknovel-bot download <url> [from] [to] [epub|txt|html]   build a book into ./out
  quicknovel-bot test [query]            search test for every provider
  quicknovel-bot mirrors <url>           other sites that have the same novel (used for failed chapters)
        """.trimIndent()
    )
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    when (args.firstOrNull()?.lowercase()) {
        null, "bot", "run" -> runBot()
        "providers" -> Providers.all.forEach { println("${it.lang}\t${it.name}\t${it.mainUrl}\t${if (it.hasRateLimit) "rate-limited" else ""}") }
        "search" -> runBlocking {
            val q = args.drop(1).joinToString(" ")
            val res = Providers.searchAll(q, Providers.all)
            Providers.rank(q, res).take(30).forEachIndexed { i, s -> println("${i + 1}. ${s.name}  [${s.apiName}]  ${s.url}") }
        }
        "info" -> runBlocking {
            val url = args.getOrNull(1) ?: return@runBlocking usage()
            val api = Providers.forUrl(url) ?: error("unsupported site")
            val r = Providers.load(api, url)
            println(Ui.novelCard(r, api.lang, 4000).replace(Regex("<[^>]+>"), ""))
            (r as? StreamResponse)?.data?.take(5)?.forEach { println(" - ${it.name}  ${it.url}") }
        }
        "download" -> runBlocking {
            val url = args.getOrNull(1) ?: return@runBlocking usage()
            val api = Providers.forUrl(url) ?: error("unsupported site")
            val r = Providers.load(api, url) as? StreamResponse ?: error("no chapters")
            val from = args.getOrNull(2)?.toIntOrNull() ?: 1
            val to = (args.getOrNull(3)?.toIntOrNull() ?: r.data.size).coerceAtMost(r.data.size)
            val fmt = BookFormat.of(args.getOrNull(4))
            val sel = (from - 1 until to).map { it to r.data[it] }
            val (chapters, failed) = Downloader.download(api, sel, true, r.name, r.data) { println("  ${it.done}/${it.total} (failed ${it.failed})" + (it.stage?.let { s -> " - $s" } ?: "")) }
            val cover = Downloader.cover(api, r.posterUrl, r.posterHeaders)
            val bytes = BookBuilder.build(fmt, BookMeta(r.name, r.author, r.synopsis, api.name, r.url, r.tags.orEmpty(), api.lang, cover), chapters)
            val out = File("out").apply { mkdirs() }.resolve("${r.name.replace(Regex("[^\\w .-]"), "")}.${fmt.ext}")
            out.writeBytes(bytes)
            println("Wrote ${out.path} (${Ui.size(bytes.size.toLong())}), failed chapters: $failed, cover: ${cover != null}")
        }
        "test" -> runBlocking {
            val q = args.drop(1).joinToString(" ").ifBlank { "the" }
            Providers.searchAll(q, Providers.all).sortedBy { it.api.name }.forEach {
                println("%-28s %4d  %5.1fs  %s".format(it.api.name, it.results.size, it.millis / 1000.0, it.error ?: ""))
            }
        }
        "mirrors" -> runBlocking {
            val url = args.getOrNull(1) ?: return@runBlocking usage()
            val api = Providers.forUrl(url) ?: error("unsupported site")
            val r = Providers.load(api, url) as? StreamResponse ?: error("no chapters")
            Mirrors.find(api, r.name, r.data.size).forEach { m ->
                val first = r.data.first()
                println("${m.api.name}: ${m.chapters.size} chapters, '${first.name}' -> ${Mirrors.match(m, 0, first, r.data.size)?.url}")
            }
        }
        "-h", "--help", "help" -> usage()
        else -> { usage(); exitProcess(1) }
    }
    exitProcess(0)
}

private fun runBot() {
    val token = Config.botToken
    if (token == null) {
        System.err.println("BOT_TOKEN is not set. Create a bot with @BotFather and put BOT_TOKEN=... in .env")
        exitProcess(1)
    }
    Store.load()
    runCatching { Downloader.cleanCache() }
    Logger.info("Data dir: ${Config.dataDir.absolutePath} · providers: ${Providers.all.size} · flaresolverr: ${com.lagradost.quicknovel.network.FlareSolverr.enabled}")
    runBlocking { Bot(TelegramApi(token)).run() }
}
