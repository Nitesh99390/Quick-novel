# QuickNovel Telegram Bot

A Telegram bot that searches **58 web-novel sites** at once and sends any novel as a clean
**EPUB / TXT / HTML** book. It uses the original, unmodified provider (scraper) code of the
[QuickNovel](https://github.com/LagradOst/QuickNovel) Android app, compiled on the JVM through small
Android shims, so new sites and fixes upstream can be pulled in with one `git submodule update`.

## Features

- 🔍 **Parallel search** across every site, with live progress and smart ranking
- 🔗 **Paste a link** from any supported site to open it directly
- 🧭 **Browse** popular / latest novels per site (with paging)
- 📖 **Novel card**: cover, author, rating, status, tags, chapter count, synopsis
- 📑 **Chapter list** and an **in-Telegram reader** (paged, next/prev chapter, prefetching)
- 📥 **Downloads**: all chapters, first/last 50/100 or a custom range (`20-150`, `300-`, `last 30`)
  - EPUB 3 (valid per epubcheck: cover, table of contents, metadata), TXT or HTML
  - progress bar with ETA, cancel button, global queue
  - parallel fetching with back-off, plus a slow second pass for failed chapters
  - shared **chapter cache** on disk, so re-downloads and updates are fast
  - big books are **split into volumes** automatically to fit Telegram's 50 MB limit
- ⭐ Library and 🕘 history per user
- ⚙️ Per-user settings: default format, search languages, enable/disable sites, author notes, covers
- 👮 Admin: `/stats`, `/broadcast`, `/ban`, `/unban`, `/cleancache`, `/testproviders`
- 🛡 Optional **FlareSolverr** integration for Cloudflare-protected sites
- No database needed: state lives in `data/store.json`

## Quick start

### Docker (recommended)

```bash
cp .env.example .env        # put BOT_TOKEN (from @BotFather) and ADMIN_IDS in it
docker compose up -d --build
docker compose logs -f bot
```

The compose file also starts FlareSolverr.

### Without Docker (JDK 17+)

```bash
git submodule update --init --depth 1   # upstream QuickNovel sources
cp .env.example .env && nano .env
./run.sh                                 # builds build/libs/quicknovel-bot.jar on first run
```

The build is tuned for 1 GB RAM machines (see `gradle.properties`). After that, the bot runs with about 150–300 MB.

## CLI (handy for testing, no token needed)

```bash
java -jar build/libs/quicknovel-bot.jar providers
java -jar build/libs/quicknovel-bot.jar search "shadow slave"
java -jar build/libs/quicknovel-bot.jar info https://novelfire.net/book/shadow-slave
java -jar build/libs/quicknovel-bot.jar download https://novelfire.net/book/shadow-slave 1 50 epub
java -jar build/libs/quicknovel-bot.jar test "the"     # health check of every site
```

## Configuration

All options are listed in [`.env.example`](.env.example). The most important ones:

| Variable | Default | Meaning |
|---|---|---|
| `BOT_TOKEN` | – | Bot token from @BotFather |
| `ADMIN_IDS` | – | Admin user ids (send `/id` to the bot to get yours) |
| `ALLOWED_USERS` | everybody | Limits the bot to these user ids |
| `FLARESOLVERR_URL` | – | e.g. `http://localhost:8191/v1` |
| `CHAPTER_PARALLELISM` | 3 | Parallel chapter requests per book |
| `MAX_CHAPTERS` | 3000 | Chapter limit per download for non-admins |
| `MAX_UPLOAD_MB` | 48 | Volume split size (raise it with a local Bot API server) |

## Commands

`/search` `/browse` `/library` `/history` `/settings` `/queue` `/cancel` `/sources` `/help`
and for admins: `/stats` `/broadcast` `/ban <id>` `/unban <id>` `/cleancache [days]` `/testproviders [query]`

## Project layout

```
src/main/kotlin/com/lagradost/quicknovel/
  bot/            Telegram bot (Bot, TelegramApi, Providers, Downloader, BookBuilder, Store, Ui, Config)
  network/        CloudflareKiller / WebViewResolver replacements (FlareSolverr)
  ...             Android shims (Uri, Base64, Log, MainActivity, ...) used by the upstream sources
upstream/QuickNovel   git submodule, compiled directly (see build.gradle.kts)
```

## Updating providers

```bash
git submodule update --remote upstream/QuickNovel
./build.sh fatJar
```

Providers that don't compile can be skipped with `./build.sh fatJar -PexcludeProviders=Foo.kt,Bar.kt`.

> Please respect the websites' terms. The bot is meant for personal offline reading.
