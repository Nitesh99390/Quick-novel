package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
  quicknovel-bot check [query]           full test per site (search, novel, 1 chapter)
  quicknovel-bot auto-test               run the auto sender once (book -> ./out, no Telegram)
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
        "check" -> runBlocking {
            // full health check per site: search -> load novel -> download one chapter
            val q = args.drop(1).joinToString(" ").ifBlank { "the" }
            val sem = Semaphore(4)
            val res = coroutineScope {
                Providers.allIncludingRemoved.map { api ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val err: String? = withTimeoutOrNull(240_000L) { Providers.check(api, q) ?: "OK" }.let { if (it == null) "timeout" else if (it == "OK") null else it }
                            println("%-28s %s".format(api.name, err ?: "OK")); System.out.flush()
                            api.name to err
                        }
                    }
                }.awaitAll()
            }
            println("\nWORKING (${res.count { it.second == null }}): " + res.filter { it.second == null }.joinToString(", ") { it.first })
            println("BROKEN  (${res.count { it.second != null }}):")
            res.filter { it.second != null }.forEach { println("${it.first}   # ${it.second}") }
        }
        "auto-test" -> runBlocking {
            // runs the auto sender once without Telegram: the book is written to ./out and the
            // novel is remembered in data/sent.json (run it again -> a different novel)
            val out = File("out").apply { mkdirs() }
            val auto = AutoSender({ _, name, bytes, caption, _, _ ->
                out.resolve(name).writeBytes(bytes)
                println("SENT  ${name} (${Ui.size(bytes.size.toLong())})\n      ${caption.replace(Regex("<[^>]+>"), "").replace("\n", "\n      ")}")
            }, { "bot" }, targetOverride = setOf(0L))
            auto.load()
            println("already sent: ${auto.count}")
            println(if (auto.sendOne()) "OK - novel sent" else "no new novel found")
            Store.save(force = true)
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
