# 📚 QuickNovel Telegram Bot

Ek Telegram bot jo **58 web-novel websites** par ek saath search karta hai aur kisi bhi novel ko
saaf-suthri **EPUB / TXT / HTML** book bana ke Telegram par hi bhej deta hai.

Ismein [QuickNovel](https://github.com/LagradOst/QuickNovel) Android app ka **original provider (scraper) code**
use hota hai, bina kisi badlaav ke. Woh code JVM par chhote "Android shims" ki madad se compile hota hai.
Isliye jab upstream mein nayi sites ya fixes aate hain, to bas ek `git submodule update` se sab mil jaata hai.

---

## 📑 Table of Contents

1. [Features](#-features)
2. [Yeh kaam kaise karta hai (Architecture)](#-yeh-kaam-kaise-karta-hai-architecture)
3. [Requirements](#-requirements)
4. [Step 1 – Telegram Bot Token banao](#-step-1--telegram-bot-token-banao)
5. [Step 2 – Repo clone karo](#-step-2--repo-clone-karo)
6. [Step 3a – Docker se chalao (recommended)](#-step-3a--docker-se-chalao-recommended)
7. [Step 3b – Bina Docker ke chalao (JDK 17+)](#-step-3b--bina-docker-ke-chalao-jdk-17)
8. [VPS par 24×7 chalana (systemd / screen / pm2)](#-vps-par-247-chalana)
9. [CLI commands (bina token ke testing)](#-cli-commands-bina-token-ke-testing)
10. [Configuration (.env)](#%EF%B8%8F-configuration-env)
11. [Bot ke commands](#-bot-ke-commands)
12. [Bot ko use kaise karein](#-bot-ko-use-kaise-karein)
13. [Providers update karna](#-providers-update-karna)
14. [Project ka structure](#-project-ka-structure)
15. [Troubleshooting](#-troubleshooting)
16. [Terminal commands ki cheat sheet](#-terminal-commands-ki-cheat-sheet)

---

## ✨ Features

- 🔍 **Parallel search**: saari sites par ek saath search, live progress ke saath, aur results smart ranking se sort hote hain
- 🔗 **Link paste karo**: kisi supported site ka link bhejo, novel seedha khul jayega
- 🧭 **Browse**: har site ke popular / latest novels dekho (paging ke saath)
- 📖 **Novel card**: cover, author, rating, status, tags, kitne chapters hain, aur synopsis
- 📑 **Chapter list** aur **Telegram ke andar reader** (pages mein, next/prev chapter, aage ke chapters pehle se load)
- 📥 **Downloads**: saare chapters, pehle/aakhri 50/100, ya apni range (`20-150`, `300-`, `last 30`)
  - EPUB 3 (epubcheck se valid: cover, table of contents, metadata), TXT ya HTML
  - ETA ke saath progress bar, cancel button, aur sab users ke liye ek global queue
  - chapters parallel mein fetch hote hain, fail hone par back-off, aur fail hue chapters ke liye ek dheemi doosri koshish
  - disk par ek shared **chapter cache**, isliye dobara download ya update jaldi hota hai
  - badi books apne aap **volumes mein split** ho jaati hain, taaki Telegram ki 50 MB limit mein fit ho sakein
- ⭐ Har user ki apni **Library** aur 🕘 **History**
- ⚙️ Har user ki apni settings: default format, search languages, sites on/off, author notes hatao, cover bhejo
- 👮 Admin ke liye: `/stats`, `/broadcast`, `/ban`, `/unban`, `/cleancache`, `/testproviders`
- 🛡 Cloudflare se protected sites ke liye optional **FlareSolverr** support
- **Database ki zarurat nahi**: saara data `data/store.json` mein rehta hai

---

## 🧠 Yeh kaam kaise karta hai (Architecture)

### Pura flow ek nazar mein

```
 ┌──────────────┐   long polling (getUpdates)   ┌────────────────────────────────────────┐
 │  Telegram    │ ◀──────────────────────────▶ │  Bot.kt  (commands, buttons, queue)    │
 │  User / Chat │   sendMessage / sendDocument │                                        │
 └──────────────┘                               └───────┬───────────────┬────────────────┘
                                                        │               │
                                  search / load / browse│               │ download job
                                                        ▼               ▼
                                         ┌────────────────────┐  ┌───────────────────────┐
                                         │  Providers.kt      │  │  Downloader.kt        │
                                         │  58 sites parallel │  │  pass 1: parallel     │
                                         │  timeout + ranking │  │  pass 2: slow retry   │
                                         └─────────┬──────────┘  │  disk chapter cache   │
                                                   │             └──────────┬────────────┘
                                                   ▼                        ▼
                              ┌──────────────────────────────────┐  ┌───────────────────┐
                              │ upstream/QuickNovel providers    │  │  BookBuilder.kt   │
                              │ (original scraper code, unchanged)│  │  EPUB / TXT / HTML│
                              │  + Android shims (Uri, Log, ...)  │  │  cover → JPEG     │
                              └──────────────┬───────────────────┘  │  volume split     │
                                             │                      └───────────────────┘
                                             ▼
                              ┌──────────────────────────────────┐
                              │ NiceHttp / OkHttp → novel sites  │
                              │ Cloudflare? → FlareSolverr (opt.)│
                              └──────────────────────────────────┘
```

### Step-by-step samjho

1. **Build ke time (Gradle)**
   - `upstream/QuickNovel` ek **git submodule** hai, jismein original QuickNovel Android app ka code hai.
   - `build.gradle.kts` ka `syncUpstream` task wahan se sirf `providers/`, `extractors/`, `MainAPI.kt`, `tools.kt`
     jaisi files ko **bina badle** `build/generated/` mein copy karta hai.
   - `generateUpstreamGlue` task upstream ki `util/Apis.kt` padhta hai aur provider registry bana deta hai
     (abhi **58 enabled, 7 disabled, 1 special**). Saath mein ek `R.kt` (resource ids) bhi banata hai.
   - Android app ka code JVM par seedha nahi chalta, isliye `src/main/kotlin/android/...` aur
     `src/main/kotlin/com/lagradost/quicknovel/...` mein **shims** hain. Yeh `Uri`, `Base64`, `Log`,
     `MainActivity`, `DataStore`, `CloudflareKiller` waghera ke chhote JVM versions hain.
   - `fatJar` task sab kuch ek file mein pack kar deta hai: `build/libs/quicknovel-bot.jar`.

2. **Startup** (`Main.kt`)
   - `.env` ya environment variables se config load hoti hai (`Config.kt`).
   - `data/store.json` se users, library, history aur stats load hote hain (`Store.kt`).
   - Purana chapter cache saaf hota hai (`CACHE_DAYS`).
   - Phir bot Telegram se **long polling** (`getUpdates`) shuru karta hai (`TelegramApi.kt`).
     Webhook ya public IP ki zarurat nahi hai.

3. **Search** (`Providers.searchAll`)
   - User koi naam bhejta hai, to saari enabled sites par **ek saath** (coroutines) search chalta hai.
   - Har site ko `SEARCH_TIMEOUT` second milte hain. Jo site slow ya band hai, woh skip ho jaati hai.
   - Results ko naam ki similarity aur site par position ke hisaab se **rank** kiya jaata hai.
   - User ki settings (language, disabled sites) yahan apply hoti hain.

4. **Novel kholna**
   - Provider ka `load()` novel ki info aur chapters ki list laata hai. Yeh kuch der ke liye memory mein cache hota hai.
   - `Ui.kt` uska card banata hai (cover, rating, tags, synopsis) aur inline buttons lagata hai.

5. **Download** (`Downloader.kt`)
   - Har job ek global queue mein jaata hai (`MAX_CONCURRENT_DOWNLOADS`).
   - **Pass 1**: chapters `CHAPTER_PARALLELISM` ke hisaab se parallel aate hain. Jis site par rate-limit hai,
     wahan ek-ek karke aate hain. Har chapter ki 4 koshish hoti hai, har baar thoda zyada wait karke.
   - **Pass 2**: jo chapters fail hue, unhe dheere-dheere ek-ek karke phir se try kiya jaata hai.
   - Har chapter ka HTML `data/cache/chapters/xx/<sha1>.html` mein save hota hai. Agli baar wahi
     chapter internet se nahi, cache se aata hai.

6. **Book banana** (`BookBuilder.kt`)
   - `ChapterCleaner` ads, duplicate titles aur (agar setting on hai) author notes hata deta hai.
   - Phir **EPUB 3** (cover, TOC, metadata), **TXT** ya **HTML** banti hai. WebP covers JPEG mein convert hote hain.
   - File `MAX_UPLOAD_MB` se badi ho to **Volume 1, 2, 3…** mein split hoti hai, phir `sendDocument` se user ko jaati hai.

7. **Cloudflare**
   - Agar kisi site par Cloudflare challenge aata hai aur `FLARESOLVERR_URL` set hai, to `CloudflareKiller`
     FlareSolverr (ek headless browser) se challenge solve karwata hai. Phir cookies aur User-Agent reuse hote hain.

---

## 📋 Requirements

| Cheez | Kab chahiye |
|---|---|
| Telegram account + Bot token | Hamesha |
| **Docker + Docker Compose** | Docker wale tarike ke liye (sabse aasaan) |
| **JDK 17+** (21 recommended) + `git` | Bina Docker ke chalane ke liye |
| RAM | Build ke liye ~1 GB, chalne ke liye ~150–300 MB |
| Internet | Novel sites aur Telegram API tak pahunch |

---

## 🤖 Step 1 – Telegram Bot Token banao

1. Telegram mein [@BotFather](https://t.me/BotFather) kholo.
2. `/newbot` bhejo, phir bot ka naam aur username (jo `bot` par khatam ho) do.
3. BotFather ek token dega, jaise `123456789:AAxxxxxxxx...`. Ise sambhaal ke rakho.
4. (Optional) `/setcommands` se command list set karo:
   ```
   search - Novel search karo
   browse - Popular novels dekho
   library - Meri library
   history - Meri history
   settings - Settings
   queue - Download queue
   cancel - Download cancel karo
   sources - Supported sites
   help - Madad
   id - Apni user id dekho
   ```
5. Apni **user id** jaanne ke liye bot chalne ke baad use `/id` bhejo, ya [@userinfobot](https://t.me/userinfobot) use karo.
   Yeh id `.env` mein `ADMIN_IDS` mein daalni hai.

---

## 📥 Step 2 – Repo clone karo

> ⚠️ Is repo mein `upstream/QuickNovel` ek **submodule** hai, isliye `--recurse-submodules` zaroor lagao.

```bash
git clone --recurse-submodules https://github.com/Nitesh99390/Quick-novel.git
cd Quick-novel

# agar pehle bina --recurse-submodules ke clone kar liya tha:
git submodule update --init --depth 1
```

---

## 🐳 Step 3a – Docker se chalao (recommended)

Docker Compose bot aur **FlareSolverr** dono ek saath chala deta hai.

```bash
# 1. Docker install karo (Ubuntu/Debian, agar pehle se nahi hai)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER      # phir ek baar logout/login karo

# 2. config file banao
cp .env.example .env
nano .env                          # BOT_TOKEN aur ADMIN_IDS bharo, phir Ctrl+O, Enter, Ctrl+X

# 3. build karke background mein start karo
docker compose up -d --build

# 4. logs dekho
docker compose logs -f bot
```

Docker ke kaam ke commands:

```bash
docker compose ps                        # kya chal raha hai
docker compose logs -f bot               # live logs (Ctrl+C se bahar)
docker compose restart bot               # restart
docker compose stop                      # rok do
docker compose down                      # rok ke containers hata do (data/ safe rehta hai)
git pull && git submodule update --init && docker compose up -d --build   # update

# container ke andar CLI chalana
docker compose run --rm bot providers
docker compose run --rm bot search "shadow slave"
```

> Note: `Dockerfile` mein agar submodule checkout nahi hua hai, to woh build ke time QuickNovel ko khud clone kar leta hai.
> Saara data `./data` folder mein rehta hai (volume mount).

---

## ☕ Step 3b – Bina Docker ke chalao (JDK 17+)

### Java install karo

```bash
# Ubuntu / Debian
sudo apt update && sudo apt install -y openjdk-21-jdk git

# Fedora
sudo dnf install -y java-21-openjdk-devel git

# macOS (Homebrew)
brew install openjdk@21 git

# Termux (Android)
pkg install openjdk-21 git

java -version          # 17 ya usse upar dikhna chahiye
```

### Build aur run

```bash
git submodule update --init --depth 1     # upstream QuickNovel sources
cp .env.example .env && nano .env         # BOT_TOKEN daalo

chmod +x run.sh build.sh gradlew
./run.sh                                  # pehli baar jar build karega, phir bot start karega
```

`run.sh` kya karta hai:
1. Agar `.env` nahi hai, to `.env.example` se bana deta hai aur ruk jaata hai (taaki aap token daal sako).
2. Agar `build/libs/quicknovel-bot.jar` nahi hai, to `./build.sh fatJar` chalata hai.
3. `java -Xmx384m -XX:+UseSerialGC -jar build/libs/quicknovel-bot.jar` se bot start karta hai.

### Sirf build karna

```bash
./build.sh fatJar           # output: build/libs/quicknovel-bot.jar
./build.sh clean fatJar     # saaf karke dobara build
```

> Build **1 GB RAM wali machines** ke liye tuned hai (`gradle.properties` mein `-Xmx600m`).
> Badi machine par build tez karne ke liye `gradle.properties` mein `-Xmx600m` ko `-Xmx1500m` kar sakte ho.
> Pehla build 5–10 minute le sakta hai, kyunki Kotlin compiler saare 68 providers compile karta hai.

### Jar ko seedha chalana

```bash
java -Xmx384m -XX:+UseSerialGC -jar build/libs/quicknovel-bot.jar          # bot
JAVA_OPTS="-Xmx512m" ./run.sh                                               # zyada memory ke saath
ENV_FILE=/path/to/other.env java -jar build/libs/quicknovel-bot.jar        # alag .env file
```

### FlareSolverr alag se chalana (Docker ke bina wale setup ke liye)

```bash
docker run -d --name flaresolverr -p 8191:8191 --restart unless-stopped ghcr.io/flaresolverr/flaresolverr:latest
# phir .env mein:
# FLARESOLVERR_URL=http://localhost:8191/v1
```

---

## 🖥 VPS par 24×7 chalana

### Option 1 – systemd (sabse achha)

```bash
sudo tee /etc/systemd/system/quicknovel-bot.service > /dev/null <<EOF
[Unit]
Description=QuickNovel Telegram Bot
After=network-online.target

[Service]
User=$USER
WorkingDirectory=$(pwd)
ExecStart=/usr/bin/java -Xmx384m -XX:+UseSerialGC -Djava.awt.headless=true -jar $(pwd)/build/libs/quicknovel-bot.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now quicknovel-bot
sudo systemctl status quicknovel-bot
journalctl -u quicknovel-bot -f           # live logs
sudo systemctl restart quicknovel-bot     # restart
```

### Option 2 – screen / tmux

```bash
screen -S novelbot       # naya session
./run.sh                 # bot chalao, phir Ctrl+A aur D dabake detach karo
screen -r novelbot       # wapas session mein jao
```

### Option 3 – pm2

```bash
npm install -g pm2
pm2 start ./run.sh --name novelbot --interpreter bash
pm2 logs novelbot
pm2 save && pm2 startup
```

---

## 🧪 CLI commands (bina token ke testing)

Jar ek CLI tool bhi hai. In commands ke liye `BOT_TOKEN` ki zarurat nahi:

```bash
java -jar build/libs/quicknovel-bot.jar help                         # usage
java -jar build/libs/quicknovel-bot.jar providers                    # saari sites ki list (lang, naam, url)
java -jar build/libs/quicknovel-bot.jar search "shadow slave"        # saari sites par search
java -jar build/libs/quicknovel-bot.jar info https://novelfire.net/book/shadow-slave
java -jar build/libs/quicknovel-bot.jar download https://novelfire.net/book/shadow-slave 1 50 epub
#                                                 ^ url                                  from to format(epub|txt|html)
#  → book ./out/ folder mein save hogi
java -jar build/libs/quicknovel-bot.jar test "the"                   # har site ka health check (results, time, error)
```

`run.sh` ke through bhi chala sakte ho: `./run.sh search "lord of the mysteries"`

---

## ⚙️ Configuration (.env)

Saare options [`.env.example`](.env.example) mein hain. Pehle environment variables padhe jaate hain, phir `.env` file.

| Variable | Default | Matlab |
|---|---|---|
| `BOT_TOKEN` | – | **Zaroori.** @BotFather se mila token |
| `ADMIN_IDS` | – | Admin user ids, comma se alag (`/id` se pata karo) |
| `ALLOWED_USERS` | sab log | Sirf in user ids ko bot use karne do. Khaali chhodo to sab use kar sakte hain |
| `MAX_CONCURRENT_DOWNLOADS` | `2` | Sab users ko milake ek saath kitni books banengi |
| `CHAPTER_PARALLELISM` | `3` | Ek book ke liye kitne chapters parallel aayenge (zyada rakhoge to site block kar sakti hai) |
| `MAX_CHAPTERS` | `3000` | Normal user ek download mein max kitne chapters le sakta hai (`0` = unlimited, admin par limit nahi) |
| `MAX_UPLOAD_MB` | `48` | Isse badi file volumes mein split hogi (Telegram Bot API ki limit 50 MB hai) |
| `CACHE_DAYS` | `14` | Itne din se purane cached chapters delete honge (`0` = kabhi nahi) |
| `SEARCH_TIMEOUT` | `25` | Har site ke liye search timeout (seconds) |
| `INCLUDE_DISABLED_PROVIDERS` | `false` | QuickNovel app mein disabled sites bhi use karo (zyaadatar band ho chuki hain) |
| `FLARESOLVERR_URL` | – | jaise `http://localhost:8191/v1`. Cloudflare wali sites ke liye |
| `TELEGRAM_API_URL` | `https://api.telegram.org` | Apna local Bot API server (2 GB tak upload, phir `MAX_UPLOAD_MB` badhao) |
| `DATA_DIR` | `data` | Store aur cache ka folder |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |

Example `.env`:

```env
BOT_TOKEN=123456789:AAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
ADMIN_IDS=987654321
ALLOWED_USERS=
CHAPTER_PARALLELISM=3
FLARESOLVERR_URL=http://localhost:8191/v1
```

---

## 💬 Bot ke commands

| Command | Kaam |
|---|---|
| `/start` | Welcome message |
| `/search <naam>` (ya `/s`) | Novel search karo. Bina command ke sirf naam bhejne se bhi search hota hai |
| `/browse` | Kisi site ke popular / latest novels |
| `/library` | Aapke saved (⭐) novels |
| `/history` | Haal mein khole gaye novels |
| `/settings` | Format, languages, sites, author notes, cover |
| `/queue` | Download queue ki position |
| `/cancel` | Aapke chal rahe downloads cancel karo |
| `/sources` | Supported sites ki list |
| `/id` | Aapki Telegram user id |
| `/help` | Madad |

**Admin commands** (sirf `ADMIN_IDS` wale users ke liye):

| Command | Kaam |
|---|---|
| `/stats` | Users, searches, downloads, cache ka size |
| `/broadcast [msg]` | Sab users ko message bhejo (HTML allowed) |
| `/ban <id>` / `/unban <id>` | User ko block / unblock karo |
| `/cleancache [days]` | Itne din se purane cached chapters delete karo |
| `/testproviders [query]` | Saari sites ka health check |

---

## 📱 Bot ko use kaise karein

1. Bot ko novel ka naam bhejo, jaise `Lord of the Mysteries`.
2. Bot saari sites par search karta hai aur live progress dikhata hai, phir ranked results deta hai.
3. Koi result chuno. Cover, author, rating, tags aur synopsis ke saath novel card khulega.
4. Buttons:
   - 📑 **Chapters**: list dekho, ya 📖 kisi chapter ko Telegram mein hi padho
   - 📥 **Download**: All / First 50 / Last 100 / **Custom range**
     - Custom range ke examples: `20-150`, `300-` (300 se aakhir tak), `last 30`
   - ⭐ **Library mein add karo**
5. Progress bar aur ETA dikhega. Book ready hote hi EPUB/TXT/HTML file aa jayegi.
6. Groups mein bot sirf commands aur links par reply karta hai.

---

## 🔄 Providers update karna

Jab QuickNovel upstream mein nayi sites ya fixes aate hain:

```bash
git submodule update --remote upstream/QuickNovel     # latest upstream code
./build.sh clean fatJar                               # dobara build
git add upstream/QuickNovel && git commit -m "chore: update QuickNovel providers"
```

Agar koi provider JVM par compile nahi hota, to use skip kar do:

```bash
./build.sh fatJar -PexcludeProviders=FooProvider.kt,BarProvider.kt
```

---

## 🗂 Project ka structure

```
.
├── build.gradle.kts          # upstream sync, provider registry generation, fatJar
├── build.sh                  # low-RAM build wrapper (single JVM, no daemon)
├── run.sh                    # .env check → build (if needed) → bot start
├── Dockerfile                # 2-stage: JDK build → JRE runtime
├── docker-compose.yml        # bot + flaresolverr
├── .env.example              # saare config options
├── gradle.properties         # 1 GB RAM ke liye tuned JVM flags
├── src/main/kotlin/
│   ├── android/…, androidx/… # Android API shims (Uri, Base64, Log, annotations)
│   └── com/lagradost/quicknovel/
│       ├── bot/
│       │   ├── Main.kt         # entry point + CLI (providers/search/info/download/test)
│       │   ├── Bot.kt          # Telegram update handling, commands, buttons, queue, reader
│       │   ├── TelegramApi.kt  # Bot API client (retry, flood-wait handling, uploads)
│       │   ├── Providers.kt    # parallel search, ranking, load cache, rate limits
│       │   ├── Downloader.kt   # 2-pass chapter download + disk cache
│       │   ├── BookBuilder.kt  # EPUB3 / TXT / HTML builder, chapter cleaner, cover → JPEG
│       │   ├── Store.kt        # users, library, history, stats → data/store.json
│       │   ├── Ui.kt           # novel cards, progress bars, formatting
│       │   ├── Config.kt       # env / .env reader
│       │   └── Logger.kt
│       ├── network/            # CloudflareKiller, FlareSolverr, WebViewResolver replacements
│       └── …                   # MainActivity, DataStore, CommonActivity shims for upstream code
├── src/shim/kotlin/          # shims ki reference copy
├── upstream/QuickNovel/      # git submodule → LagradOst/QuickNovel (v3.8.1)
└── data/                     # (runtime) store.json + cache/chapters/
```

---

## 🛠 Troubleshooting

| Problem | Solution |
|---|---|
| `BOT_TOKEN is not set` | `.env` mein `BOT_TOKEN=...` daalo, aur wahi folder se chalao jahan `.env` hai |
| `MainAPI.kt` not found / `upstream/QuickNovel` khaali hai | `git submodule update --init --depth 1` |
| Build mein `OutOfMemoryError` / `Metaspace` | `gradle.properties` mein `-Xmx600m` ko `-Xmx1500m` karo, ya swap add karo (neeche dekho) |
| `Unsupported class file major version` | Java 17+ chahiye: `java -version` check karo |
| Kuch sites results nahi de rahi | `java -jar build/libs/quicknovel-bot.jar test` chalao. Cloudflare wali sites ke liye FlareSolverr lagao |
| Chapters fail ho rahe hain / site block kar rahi hai | `CHAPTER_PARALLELISM=1` ya `2` karo |
| `409 Conflict: terminated by other getUpdates` | Bot do jagah chal raha hai. Ek instance band karo |
| File 50 MB se badi hai | Yeh apne aap volumes mein split ho jaati hai. Ya local Bot API server lagake `MAX_UPLOAD_MB` badhao |

Kam RAM wale VPS par swap add karna:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## 📝 Terminal commands ki cheat sheet

```bash
# ---------- setup ----------
git clone --recurse-submodules https://github.com/Nitesh99390/Quick-novel.git
cd Quick-novel
cp .env.example .env && nano .env

# ---------- Docker ----------
docker compose up -d --build
docker compose logs -f bot
docker compose restart bot
docker compose down

# ---------- bina Docker ----------
git submodule update --init --depth 1
chmod +x run.sh build.sh gradlew
./build.sh fatJar
./run.sh

# ---------- CLI ----------
java -jar build/libs/quicknovel-bot.jar providers
java -jar build/libs/quicknovel-bot.jar search "shadow slave"
java -jar build/libs/quicknovel-bot.jar info <novel-url>
java -jar build/libs/quicknovel-bot.jar download <novel-url> 1 50 epub
java -jar build/libs/quicknovel-bot.jar test

# ---------- update ----------
git pull && git submodule update --init
git submodule update --remote upstream/QuickNovel
./build.sh clean fatJar
```

---

## 🙏 Credits & Disclaimer

- Provider / scraper code: [LagradOst/QuickNovel](https://github.com/LagradOst/QuickNovel) (submodule, bina badlaav ke)
- HTTP: [NiceHttp](https://github.com/Blatzar/NiceHttp), HTML parsing: [jsoup](https://jsoup.org/)

> ⚠️ Websites ki terms ka dhyaan rakhein. Yeh bot sirf **personal offline reading** ke liye hai.
