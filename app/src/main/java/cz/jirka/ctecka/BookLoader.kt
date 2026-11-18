package cz.jirka.ctecka

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class Book(val id: String, val title: String, val sentences: List<String>)

/** Nacte knihu (PDF / EPUB / TXT), rozseka ji na vety a ulozi do cache, at je pristi otevreni okamzite. */
object BookLoader {

    private fun dir(ctx: Context) = File(ctx.filesDir, "knihy").apply { mkdirs() }

    fun loadCached(ctx: Context, id: String, title: String): Book? {
        val f = File(dir(ctx), "$id.txt")
        if (!f.exists()) return null
        val s = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        return if (s.isEmpty()) null else Book(id, title, s)
    }

    fun load(ctx: Context, uri: Uri, progress: (String) -> Unit): Book {
        var name = uri.lastPathSegment ?: "kniha"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val title = name.substringBeforeLast('.')
        val id = sha1("$name|$size")
        loadCached(ctx, id, title)?.let { return it }

        val tmp = File(ctx.cacheDir, "kniha.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        val head = tmp.inputStream().use { val b = ByteArray(8); val n = it.read(b); b.copyOf(maxOf(n, 0)) }
        val lower = name.lowercase()

        val text = try {
            when {
                lower.endsWith(".pdf") || head.startsWith("%PDF") -> pdf(ctx, tmp, progress)
                lower.endsWith(".epub") || head.startsWith("PK") -> epub(tmp)
                else -> decode(tmp.readBytes())
            }
        } finally {
            tmp.delete()
        }

        progress("Dělím text na věty…")
        val sentences = split(text)
        if (sentences.isEmpty()) {
            throw IllegalStateException("V souboru jsem nenašel žádný text. Jestli je to naskenované PDF (obrázky stránek), přečíst ho neumím.")
        }
        File(dir(ctx), "$id.txt").writeText(sentences.joinToString("\n"), Charsets.UTF_8)
        return Book(id, title, sentences)
    }

    // ---------- PDF ----------
    private fun pdf(ctx: Context, f: File, progress: (String) -> Unit): String {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        return PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            val pages = doc.numberOfPages
            val sb = StringBuilder()
            val stripper = PDFTextStripper()
            var p = 1
            while (p <= pages) {
                val end = minOf(p + 9, pages)
                progress("Čtu PDF: strana $p z $pages")
                stripper.startPage = p
                stripper.endPage = end
                sb.append(cleanPdf(stripper.getText(doc))).append("\n\n")
                p = end + 1
            }
            sb.toString()
        }
    }

    /** Spoji radky PDF do odstavcu, slepi rozdelena slova (slo-\nvo), vyhodi cisla stran. */
    private fun cleanPdf(raw: String): String {
        val lines = raw.replace("\r", "").replace("­", "").split("\n").map { it.trim() }
        val out = StringBuilder()
        var prevEmpty = true
        for (line in lines) {
            if (line.isEmpty()) {
                if (!prevEmpty) out.append("\n")
                prevEmpty = true
                continue
            }
            if (line.matches(Regex("""[\d\s\-–—.]{1,8}"""))) continue // cislo strany
            if (out.isNotEmpty() && !prevEmpty) {
                val last = out.last()
                if (last == '-' && line.first().isLowerCase()) {
                    out.setLength(out.length - 1)
                } else {
                    out.append(' ')
                }
            }
            out.append(line)
            prevEmpty = false
        }
        return out.toString()
    }

    // ---------- EPUB ----------
    private fun epub(f: File): String {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(f.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                val l = n.lowercase()
                if (!e.isDirectory && (l.endsWith(".opf") || l.endsWith(".xhtml") || l.endsWith(".html") ||
                            l.endsWith(".htm") || l.endsWith("container.xml"))
                ) files[n] = z.readBytes()
            }
        }
        val container = files.entries.firstOrNull { it.key.endsWith("container.xml") }?.value?.toString(Charsets.UTF_8)
        val opfPath = container?.let { Regex("""full-path="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: files.keys.firstOrNull { it.lowercase().endsWith(".opf") }
        val order = mutableListOf<String>()
        if (opfPath != null && files[opfPath] != null) {
            val opf = files[opfPath]!!.toString(Charsets.UTF_8)
            val base = opfPath.substringBeforeLast('/', "")
            val manifest = HashMap<String, String>()
            Regex("""<item\b[^>]*>""").findAll(opf).forEach { m ->
                val tag = m.value
                val id = Regex("""\bid="([^"]+)"""").find(tag)?.groupValues?.get(1)
                val href = Regex("""\bhref="([^"]+)"""").find(tag)?.groupValues?.get(1)
                if (id != null && href != null) manifest[id] = href
            }
            Regex("""<itemref\b[^>]*idref="([^"]+)"""").findAll(opf).forEach { m ->
                manifest[m.groupValues[1]]?.let { href ->
                    val h = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
                    order += if (base.isEmpty()) h else "$base/$h"
                }
            }
        }
        if (order.isEmpty()) order += files.keys.filter { it.lowercase().matches(Regex(""".*\.x?html?$""")) }.sorted()
        val sb = StringBuilder()
        for (p in order) {
            val html = files[p]?.toString(Charsets.UTF_8) ?: continue
            val body = html
                .replace(Regex("""(?is)<head.*?</head>"""), "")
                .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), "")
            @Suppress("DEPRECATION")
            sb.append(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString()).append("\n\n")
        }
        return sb.toString()
    }

    // ---------- TXT ----------
    private fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1250"))
        }
    }

    // ---------- deleni na vety ----------
    private val sentenceEnd = Regex("""(?<=[.!?…][“”"»’)]?)\s+(?=[\p{Lu}\p{N}„"«(–—-])""")

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split(Regex("""\n+"""))) {
            val p = para.replace(Regex("""\s+"""), " ").trim()
            if (p.isEmpty() || p.none { it.isLetter() }) continue
            for (s in p.split(sentenceEnd)) {
                var rest = s.trim()
                while (rest.length > 400) {
                    var cut = rest.lastIndexOf(", ", 300)
                    if (cut < 100) cut = rest.lastIndexOf(' ', 300)
                    if (cut < 50) cut = 300
                    out += rest.substring(0, cut + 1).trim()
                    rest = rest.substring(cut + 1).trim()
                }
                if (rest.any { it.isLetterOrDigit() }) out += rest
            }
        }
        return out
    }

    private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
