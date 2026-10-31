package com.lagradost.quicknovel.bot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.safety.Safelist
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

enum class BookFormat(val ext: String, val mime: String, val label: String) {
    EPUB("epub", "application/epub+zip", "📘 EPUB"),
    TXT("txt", "text/plain", "📄 TXT"),
    HTML("html", "text/html", "🌐 HTML");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: EPUB
    }
}

data class Chapter(val index: Int, val title: String, val html: String)

data class BookMeta(
    val title: String,
    val author: String?,
    val synopsis: String?,
    val source: String,
    val sourceUrl: String,
    val tags: List<String>,
    val language: String,
    val cover: ByteArray?,
)

/** Cleans and converts chapter HTML. Mirrors BookDownloader2.getChapter from the app. */
object ChapterCleaner {
    private val chapterRegex = Regex("Chapter \\d+", RegexOption.IGNORE_CASE)
    private val translatorRegex = Regex("Translator:.*?Editor:.*", RegexOption.IGNORE_CASE)
    private val headingRegex = Regex("^\\s*(chapter|ch\\.?|chap\\.?|episode|ep\\.?|capítulo|capitulo|chapitre|bab|глава|الفصل)\\s*\\d+", RegexOption.IGNORE_CASE)
    private val numRegex = Regex("\\d+")

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("^\\s*(chapter|ch\\.?|chap\\.?)\\s*\\d+\\s*[:\\-–—.]*"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun sameNumber(a: String, b: String) = numRegex.find(a)?.value?.toIntOrNull() == numRegex.find(b)?.value?.toIntOrNull()

    fun clean(title: String, html: String, stripAuthorNotes: Boolean): String {
        val document = Jsoup.parse(html)
        document.outputSettings().apply {
            syntax(Document.OutputSettings.Syntax.xml)
            escapeMode(Entities.EscapeMode.xhtml)
            prettyPrint(false)
        }
        if (stripAuthorNotes) document.select("div.qnauthornotecontainer").remove()
        document.select("script, style, iframe, noscript, form, input, button, ins, object, embed, svg, link, meta").remove()
        // images in chapters point to remote servers; readers can't load them offline anyway
        document.select("img").remove()
        document.select("[style]").removeAttr("style")
        document.select("[class]").removeAttr("class")
        document.select("[id]").removeAttr("id")
        document.select("a").unwrap()

        val body: Element = document.body()
        // Strip leading lines that only repeat the chapter title (we add our own heading)
        val normTitle = norm(title)
        repeat(2) {
            val first = body.select("p, h1, h2, h3, h4, h5, h6, strong, b").firstOrNull { it.text().isNotBlank() } ?: return@repeat
            val text = first.text().trim()
            val n = norm(text)
            val redundant = text.length < 200 && (
                    text.equals(title.trim(), true) || text.matches(chapterRegex) || text.matches(translatorRegex) ||
                            n == normTitle || (n.length > 8 && (normTitle.endsWith(n) || n.endsWith(normTitle))) ||
                            (headingRegex.containsMatchIn(text) && sameNumber(text, title) && n.length <= normTitle.length + 25))
            if (!redundant) return@repeat
            first.remove()
        }
        // collapse empty paragraphs
        body.select("p").filter { it.text().isBlank() && it.select("br").isEmpty() }.forEach { it.remove() }
        return body.html()
    }

    /** Plain text with paragraphs separated by blank lines */
    fun toText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        doc.select("br").before("\\n")
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote").before("\\n\\n")
        val s = Jsoup.clean(doc.html().replace("\\n", "\n"), "", Safelist.none(), Document.OutputSettings().prettyPrint(false))
        return org.jsoup.parser.Parser.unescapeEntities(s, false)
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

object BookBuilder {
    private fun esc(s: String?): String = Entities.escape(s ?: "").replace("\u0000", "")

    private const val CSS = """
body { font-family: serif; line-height: 1.55; margin: 0 4%; text-align: justify; }
h1, h2 { text-align: center; line-height: 1.25; margin: 1.2em 0 1em; }
h2.chapter { font-size: 1.35em; page-break-before: always; }
p { margin: 0 0 0.8em; text-indent: 0; }
.meta { text-align: center; color: #555; }
.synopsis { margin-top: 2em; }
.cover { text-align: center; margin: 0; padding: 0; }
.cover img { max-width: 100%; max-height: 100%; }
"""

    fun build(format: BookFormat, meta: BookMeta, chapters: List<Chapter>): ByteArray = when (format) {
        BookFormat.EPUB -> epub(meta, chapters)
        BookFormat.TXT -> txt(meta, chapters)
        BookFormat.HTML -> html(meta, chapters)
    }

    /** "Title (Ch 1-50) - Vol 01.epub" */
    fun fileName(title: String, a: Int, b: Int, total: Int, ext: String, part: Int? = null): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "novel" }
        val range = if (a == 1 && b == total) "" else " (Ch $a-$b)"
        val p = part?.let { " - Vol ${"%02d".format(it)}" } ?: ""
        return "$clean$range$p.$ext"
    }

    /**
     * Builds the book and splits it into volumes when a file would be larger than [maxBytes]
     * (Telegram upload limit). Returns (file name, bytes) pairs.
     */
    fun buildVolumes(format: BookFormat, meta: BookMeta, chapters: List<Chapter>, maxBytes: Long, title: String, total: Int): List<Pair<String, ByteArray>> {
        var parts = 1
        while (true) {
            val chunks = if (parts == 1) listOf(chapters) else chapters.chunked((chapters.size + parts - 1) / parts)
            val files = chunks.mapIndexed { i, chs ->
                val f = chs.first().index + 1; val l = chs.last().index + 1
                val m = if (parts == 1) meta else meta.copy(title = "${meta.title} (Vol ${i + 1}: Ch $f-$l)")
                fileName(title, f, l, if (parts == 1) total else -1, format.ext, if (parts == 1) null else i + 1) to build(format, m, chs)
            }
            val biggest = files.maxOf { it.second.size }
            if (biggest <= maxBytes || parts >= chapters.size) return files
            parts = maxOf(parts + 1, (parts * biggest.toDouble() / maxBytes * 1.1).toInt() + 1)
        }
    }

    // ------------------------------------------------------------------ TXT

    private fun txt(meta: BookMeta, chapters: List<Chapter>): ByteArray = buildString {
        appendLine(meta.title)
        meta.author?.let { appendLine("by $it") }
        appendLine("Source: ${meta.source} - ${meta.sourceUrl}")
        if (meta.tags.isNotEmpty()) appendLine("Tags: ${meta.tags.joinToString()}")
        meta.synopsis?.let { appendLine(); appendLine(it.trim()) }
        appendLine(); appendLine("=".repeat(40)); appendLine()
        chapters.forEach { c ->
            appendLine(c.title); appendLine("-".repeat(c.title.length.coerceIn(3, 60))); appendLine()
            appendLine(ChapterCleaner.toText(c.html)); appendLine(); appendLine()
        }
    }.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ HTML

    private fun html(meta: BookMeta, chapters: List<Chapter>): ByteArray = buildString {
        append("<!DOCTYPE html>\n<html lang=\"${esc(meta.language)}\"><head><meta charset=\"utf-8\"/>")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
        append("<title>${esc(meta.title)}</title><style>$CSS body{max-width:46em;margin:auto;padding:1em}")
        append(" nav a{display:block;padding:.2em 0} @media (prefers-color-scheme: dark){body{background:#111;color:#ddd} a{color:#8ab4f8}}</style></head><body>")
        if (meta.cover != null) {
            append("<div class=\"cover\"><img alt=\"cover\" style=\"max-height:60vh\" src=\"data:${imageMime(meta.cover)};base64,")
            append(java.util.Base64.getEncoder().encodeToString(meta.cover)); append("\"/></div>")
        }
        append("<h1>${esc(meta.title)}</h1>")
        meta.author?.let { append("<p class=\"meta\">by ${esc(it)}</p>") }
        append("<p class=\"meta\"><a href=\"${esc(meta.sourceUrl)}\">${esc(meta.source)}</a></p>")
        meta.synopsis?.let { s -> append("<div class=\"synopsis\">"); s.split("\n").filter { it.isNotBlank() }.forEach { append("<p>${esc(it.trim())}</p>") }; append("</div>") }
        append("<h2>Contents</h2><nav>")
        chapters.forEach { append("<a href=\"#c${it.index}\">${esc(it.title)}</a>") }
        append("</nav>")
        chapters.forEach { append("<h2 class=\"chapter\" id=\"c${it.index}\">${esc(it.title)}</h2>").append(it.html) }
        append("</body></html>")
    }.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ EPUB 3 (with EPUB 2 NCX fallback)

    private fun xhtml(title: String, lang: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${esc(lang)}" lang="${esc(lang)}">
<head><meta charset="utf-8"/><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body
</body>
</html>"""

    fun imageMime(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun epub(meta: BookMeta, chapters: List<Chapter>): ByteArray {
        val uuid = UUID.nameUUIDFromBytes((meta.source + meta.sourceUrl).toByteArray()).toString()
        val lang = meta.language.ifBlank { "en" }
        val modified = Instant.now().toString().substringBefore('.') + "Z"
        val cover = meta.cover?.let { toJpeg(it, 1400) ?: it }
        val coverMime = cover?.let { imageMime(it) }
        val coverExt = when (coverMime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype must be the first, uncompressed entry
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimeBytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimeBytes) }.value
            })
            zip.write(mimeBytes); zip.closeEntry()

            fun putBytes(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun put(name: String, text: String) = putBytes(name, text.toByteArray(Charsets.UTF_8))

            put(
                "META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
            )
            put("OEBPS/style.css", CSS)
            if (cover != null) {
                putBytes("OEBPS/cover.$coverExt", cover)
                put("OEBPS/cover.xhtml", xhtml("Cover", lang, "<div class=\"cover\"><img src=\"cover.$coverExt\" alt=\"Cover\"/></div>"))
            }

            val info = buildString {
                append("<h1>${esc(meta.title)}</h1>")
                meta.author?.let { append("<p class=\"meta\">by ${esc(it)}</p>") }
                append("<p class=\"meta\">${esc(meta.source)}</p>")
                if (meta.tags.isNotEmpty()) append("<p class=\"meta\"><small>${esc(meta.tags.joinToString(" · "))}</small></p>")
                meta.synopsis?.let { s ->
                    append("<div class=\"synopsis\">")
                    s.split("\n").filter { it.isNotBlank() }.forEach { append("<p>${esc(it.trim())}</p>") }
                    append("</div>")
                }
            }
            put("OEBPS/info.xhtml", xhtml(meta.title, lang, info))

            chapters.forEach { c ->
                put("OEBPS/ch${c.index}.xhtml", xhtml(c.title, lang, "<h2 class=\"chapter\">${esc(c.title)}</h2>\n${c.html}"))
            }

            put("OEBPS/nav.xhtml", xhtml("Contents", lang, buildString {
                append("<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>")
                append("<li><a href=\"info.xhtml\">${esc(meta.title)}</a></li>")
                chapters.forEach { append("<li><a href=\"ch${it.index}.xhtml\">${esc(it.title)}</a></li>") }
                append("</ol></nav>")
            }))

            put("OEBPS/toc.ncx", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">")
                append("<head><meta name=\"dtb:uid\" content=\"urn:uuid:$uuid\"/><meta name=\"dtb:depth\" content=\"1\"/></head>")
                append("<docTitle><text>${esc(meta.title)}</text></docTitle><navMap>")
                append("<navPoint id=\"np-info\" playOrder=\"1\"><navLabel><text>${esc(meta.title)}</text></navLabel><content src=\"info.xhtml\"/></navPoint>")
                chapters.forEachIndexed { i, c ->
                    append("<navPoint id=\"np${c.index}\" playOrder=\"${i + 2}\"><navLabel><text>${esc(c.title)}</text></navLabel><content src=\"ch${c.index}.xhtml\"/></navPoint>")
                }
                append("</navMap></ncx>")
            })

            put("OEBPS/content.opf", buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"${esc(lang)}\">")
                append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
                append("<dc:identifier id=\"bookid\">urn:uuid:$uuid</dc:identifier>")
                append("<dc:title>${esc(meta.title)}</dc:title><dc:language>${esc(lang)}</dc:language>")
                meta.author?.let { append("<dc:creator>${esc(it)}</dc:creator>") }
                meta.synopsis?.let { append("<dc:description>${esc(it.take(4000))}</dc:description>") }
                append("<dc:publisher>${esc(meta.source)}</dc:publisher><dc:source>${esc(meta.sourceUrl)}</dc:source>")
                meta.tags.take(20).forEach { append("<dc:subject>${esc(it)}</dc:subject>") }
                append("<meta property=\"dcterms:modified\">$modified</meta>")
                if (cover != null) append("<meta name=\"cover\" content=\"cover-image\"/>")
                append("</metadata><manifest>")
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
                append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
                if (cover != null) {
                    append("<item id=\"cover-image\" href=\"cover.$coverExt\" media-type=\"$coverMime\" properties=\"cover-image\"/>")
                    append("<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>")
                }
                append("<item id=\"info\" href=\"info.xhtml\" media-type=\"application/xhtml+xml\"/>")
                chapters.forEach { append("<item id=\"ch${it.index}\" href=\"ch${it.index}.xhtml\" media-type=\"application/xhtml+xml\"/>") }
                append("</manifest><spine toc=\"ncx\">")
                if (cover != null) append("<itemref idref=\"cover\" linear=\"yes\"/>")
                append("<itemref idref=\"info\"/>")
                chapters.forEach { append("<itemref idref=\"ch${it.index}\"/>") }
                append("</spine>")
                if (cover != null) append("<guide><reference type=\"cover\" title=\"Cover\" href=\"cover.xhtml\"/></guide>")
                append("</package>")
            })
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ images

    init {
        // the fat jar may drop META-INF/services entries, register the WebP reader explicitly
        runCatching {
            javax.imageio.spi.IIORegistry.getDefaultInstance()
                .registerServiceProvider(com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi())
        }
    }

    /** Re-encodes an image as JPEG with max side [maxSide]. Returns null when ImageIO can't read it (e.g. webp). */
    fun toJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int = Int.MAX_VALUE): ByteArray? = try {
        val src = ImageIO.read(ByteArrayInputStream(bytes))
        if (src == null) null else {
            var side = maxSide
            var result: ByteArray
            do {
                val scale = minOf(1.0, side.toDouble() / maxOf(src.width, src.height))
                val w = maxOf(1, (src.width * scale).toInt())
                val h = maxOf(1, (src.height * scale).toInt())
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
                g.drawImage(src, 0, 0, w, h, null); g.dispose()
                val bos = ByteArrayOutputStream()
                ImageIO.write(img, "jpg", bos)
                result = bos.toByteArray()
                side = (side * 0.75).toInt()
            } while (result.size > maxBytes && side > 64)
            result
        }
    } catch (t: Throwable) {
        null
    }
}
