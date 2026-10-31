# 📚 QuickNovel Telegram Bot

Ek Telegram bot jo **35 chalti hui web-novel websites** par ek saath search karta hai aur kisi bhi novel ko
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

- 🤖 **Auto Send (naya)**: bot **khud se** sites ki popular/latest list se naye novels dhoondhta hai, book banata hai aur
  aapko Telegram par bhejta rehta hai. Aapki permission ya koi button dabane ki zarurat nahi.
  - Jo novel ek baar bhej diya, woh `data/sent.json` mein save hota hai aur **dobara kabhi scrape ya send nahi hota**
    (site+link aur novel ke naam dono se check hota hai, isliye doosri site par wahi novel ho to bhi skip hota hai)
  - Jo novel download nahi ho pata, use 3 baar ke baad chhod deta hai
  - Details: [🤖 Auto Send](#-auto-send-khud-se-novel-bhejna)
- 🧹 **Kharab sites hata di gayi**: jo 23 sites novel nahi de rahi thi, woh search, browse aur auto send se hata di gayi hain
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
- 👮 Admin ke liye: `/auto`, `/stats`, `/broadcast`, `/ban`, `/unban`, `/cleancache`, `/testproviders`
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
java -jar build/libs/quicknovel-bot.jar test "the"                   # har site ka search test (results, time, error)
java -jar build/libs/quicknovel-bot.jar check                        # poora test: search → novel page → 1 chapter, har site ka
java -jar build/libs/quicknovel-bot.jar auto-test                    # auto sender ek baar chalao (book ./out mein, Telegram nahi)
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
| `/menu` | 🏠 Main menu (har screen par ⬅️ Back / 🏠 Menu buttons bhi hain) |
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
| `/auto` | Auto send ka status: on/off, kitne novels bhej chuka, aakhri 10 novels, agla run kab |
| `/auto on` / `/auto off` | Auto send chalu / band karo (restart ke baad bhi yaad rehta hai) |
| `/auto now` | Abhi turant ek naya novel dhoondh ke bhejo |

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
   - Agar kisi site se kuch chapters nahi aate, to bot **apne aap doosri site** (jismein wahi novel ho) se woh chapters le aata hai
   - Kuch sites (jaise NovelFire) chapters ko doosri site par redirect karti hain, bot yeh redirect khud follow karta hai
6. Har screen par **⬅️ Back** (results / novel / library par wapas) aur **🏠 Menu** button hai.
7. Groups mein bot sirf commands aur links par reply karta hai.

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
│       │   ├── Downloader.kt   # 3-pass chapter download (parallel → slow retry → mirror site) + disk cache
│       │   ├── Mirrors.kt      # same novel on other sites dhoondhna + chapter matching
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

## 🤖 Auto Send (khud se novel bhejna)

Bot start hote hi ek background kaam chalta hai jo **bina aapse pooche** naye novels bhejta rehta hai:

1. Har `AUTO_INTERVAL_MIN` minute (default 30) mein bot ek-ek karke working sites ki **popular / latest list** dekhta hai.
2. List mein pehla aisa novel chunta hai jo **pehle kabhi nahi bheja gaya**. Bheje hue novels bina load kiye hi skip ho jaate hain.
3. Novel ke chapters download karta hai (default pehle 500, `AUTO_MAX_CHAPTERS`), EPUB banata hai, aur cover ke saath bhejta hai.
4. Novel `data/sent.json` mein likh diya jaata hai. Restart ke baad bhi yaad rehta hai, isliye **wahi novel dobara nahi aayega**.
   Check site+link se bhi hota hai aur naam se bhi, isliye agar wahi novel doosri site par bhi hai to bhi skip ho jayega.
5. Agar 10% se zyada chapters fail ho jaayein, to adhoori book nahi bhejta. 3 baar fail hone ke baad woh novel chhod deta hai.

**Kisko bhejta hai?** `AUTO_CHAT_IDS` mein jo ids hain unko. Agar yeh khaali hai to `ADMIN_IDS` ko.
Channel ya group mein bhejne ke liye bot ko wahan admin banao aur uski id (`-100…`) `AUTO_CHAT_IDS` mein daalo.

```env
ADMIN_IDS=987654321
AUTO_SEND=true
AUTO_CHAT_IDS=987654321,-1001234567890   # aap + ek channel
AUTO_INTERVAL_MIN=30
AUTO_NOVELS_PER_RUN=1
AUTO_MAX_CHAPTERS=500                     # 0 = saare chapters
AUTO_LANGUAGES=en
AUTO_SITES=                               # khaali = saari working sites, ya: NovelBin,FreeWebNovel,LibRead
```

Telegram se control: `/auto` (status), `/auto off`, `/auto on`, `/auto now`.
Sab kuch dobara shuru se bhejna ho to bot band karke `data/sent.json` delete kar do.

### Hatayi gayi sites

`java -jar build/libs/quicknovel-bot.jar check` se har site ka poora test kiya gaya (search → novel → chapter ka text).
Jo **23 sites** do alag tests mein fail hui, woh [`src/main/resources/removed_providers.txt`](src/main/resources/removed_providers.txt)
mein hain aur bot unhe use nahi karta: AllNovel, Annas Archive, Chikari, Chrysanthemum Garden, FanMTL, FenrirRealm,
Galaxy Novels, Graycity, KolNovel, Light Novel Translations, MeioNovel, Nova, NovelFull-Net, NovLove, RanobeHub, Ranobes,
Ranovel, Royal Road, SakuraNovel, Scribblehub, Wattpad, WTR-LAB, WuxiaBox.

Agar baad mein koi site theek ho jaaye, to bina rebuild ke `.env` mein `KEEP_PROVIDERS=Royal Road,Wattpad` likh do.
Aur koi site hatani ho to `REMOVED_PROVIDERS=Name1,Name2`.

> Note: kuch sites (jaise Royal Road, Scribblehub) Cloudflare se datacenter IP ko block karti hain. Aapke server par
> `FLARESOLVERR_URL` ke saath chal sakti hain. Tab unhe `KEEP_PROVIDERS` se wapas la sakte ho.

---

## 🛠 Troubleshooting

| Problem | Solution |
|---|---|
| `BOT_TOKEN is not set` | `.env` mein `BOT_TOKEN=...` daalo, aur wahi folder se chalao jahan `.env` hai |
| `MainAPI.kt` not found / `upstream/QuickNovel` khaali hai | `git submodule update --init --depth 1` |
| Build mein `OutOfMemoryError` / `Metaspace` | `gradle.properties` mein `-Xmx600m` ko `-Xmx1500m` karo, ya swap add karo (neeche dekho) |
| `Unsupported class file major version` | Java 17+ chahiye: `java -version` check karo |
| Auto send kuch nahi bhej raha | `/auto` se status dekho. `ADMIN_IDS` ya `AUTO_CHAT_IDS` set hona chahiye, aur aapne bot ko pehle `/start` bheja ho. Channel ke liye bot admin hona chahiye |
| Kuch sites results nahi de rahi | `java -jar build/libs/quicknovel-bot.jar check` chalao. Cloudflare wali sites ke liye FlareSolverr lagao |
| Chapters fail ho rahe hain / site block kar rahi hai | `CHAPTER_PARALLELISM=1` ya `2` karo. Missing chapters bot doosri sites se laane ki koshish karta hai. `java -jar build/libs/quicknovel-bot.jar mirrors <novel-url>` se dekho kaunsi sites par wahi novel hai |
| `Royal Road failed … 403 / error code: 1005` | Royal Road (Cloudflare) aapke server ke IP/ASN ko block kar raha hai (aksar datacenter/VPS IP). Bot ab app-API fail hone par website scraper try karta hai, lekin pura IP block ho to koi code fix nahi kar sakta: doosra VPS/residential IP use karo, ya wahi novel kisi doosri site se lo |
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
