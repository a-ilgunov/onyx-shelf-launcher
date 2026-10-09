package ru.efimov.booklib.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Html
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    // FB2 из интернета часто содержат &nbsp; и прочие неописанные сущности
    try {
        setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
    } catch (_: Exception) {
    }
    setInput(input, null)
}

/** Имя тега без префикса пространства имён: «dc:title» → «title». */
private fun local(name: String?): String = name?.substringAfter(':') ?: ""

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        if (local(getAttributeName(i)) == name) return getAttributeValue(i)
    }
    return null
}

object Fb2Parser {
    fun parseFile(file: File, format: String, meta: BookMeta) {
        if (format == "fb2.zip") {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: return
                    if (e.name.lowercase().endsWith(".fb2")) {
                        parse(zip, meta)
                        return
                    }
                }
            }
        } else {
            file.inputStream().buffered(64 * 1024).use { parse(it, meta) }
        }
    }

    /** Битые файлы не страшны: всё, что успели прочитать до ошибки, остаётся в [meta]. */
    fun parse(input: InputStream, meta: BookMeta) {
        try {
            parseInternal(input, meta)
        } catch (_: Exception) {
        }
    }

    private fun parseInternal(input: InputStream, meta: BookMeta) {
        val p = newParser(input)
        val stack = ArrayList<String>()
        val text = StringBuilder()
        val ann = StringBuilder()
        var coverId: String? = null
        var descDone = false
        var first = ""
        var middle = ""
        var last = ""
        var nick = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = local(p.name)
                    if (descDone) {
                        if (name == "binary" && p.attr("id") == coverId) {
                            meta.coverBytes = Base64.decode(p.nextText(), Base64.DEFAULT)
                            return
                        }
                    } else {
                        val parent = stack.lastOrNull()
                        val inTitleInfo = "title-info" in stack
                        if (inTitleInfo) {
                            when {
                                name == "author" && parent == "title-info" -> {
                                    first = ""; middle = ""; last = ""; nick = ""
                                }
                                // Серий бывает несколько, в том числе вложенных: собираем все по порядку
                                name == "sequence" && (parent == "title-info" || parent == "sequence") -> {
                                    val sname = p.attr("name")?.trim()?.takeIf { it.isNotEmpty() }
                                    if (sname != null && meta.series.none { it.name.equals(sname, true) }) {
                                        meta.series.add(SeriesRef(sname, p.attr("number")?.trim()?.replace(',', '.')?.toFloatOrNull()))
                                    }
                                }
                                name == "image" && parent == "coverpage" && coverId == null ->
                                    coverId = p.attr("href")?.removePrefix("#")
                                name == "p" && "annotation" in stack && ann.isNotEmpty() -> ann.append("\n")
                            }
                        }
                        stack.add(name)
                        text.setLength(0)
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> if (!descDone) {
                    val t = p.text ?: ""
                    if ("annotation" in stack && "title-info" in stack) ann.append(t) else text.append(t)
                }

                XmlPullParser.END_TAG -> if (!descDone) {
                    val name = local(p.name)
                    stack.removeLastOrNull()
                    if ("title-info" in stack || name == "title-info") {
                        val value = text.toString().trim().replace(Regex("\\s+"), " ")
                        when (name) {
                            "first-name" -> first = value
                            "middle-name" -> middle = value
                            "last-name" -> last = value
                            "nickname" -> nick = value
                            "author" -> if (stack.lastOrNull() == "title-info") {
                                val f = listOf(first, middle).filter { it.isNotBlank() }.joinToString(" ")
                                val a = Author(f, last.ifBlank { nick })
                                if (a.first.isNotBlank() || a.last.isNotBlank()) meta.authors.add(a)
                            }
                            "book-title" -> if (meta.title == null && value.isNotEmpty()) meta.title = value
                            "genre" -> if (value.isNotEmpty() && value !in meta.genres) meta.genres.add(value)
                            "lang" -> if (meta.lang == null && value.isNotEmpty()) meta.lang = value
                            "annotation" -> meta.annotation = cleanAnnotation(ann.toString())
                        }
                    }
                    text.setLength(0)
                    if (name == "description") {
                        descDone = true
                        if (coverId == null) return
                    }
                }
            }
            ev = p.next()
        }
    }

    private fun cleanAnnotation(s: String): String? =
        s.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
            .joinToString("\n").takeIf { it.isNotEmpty() }
}

object EpubParser {
    fun parseFile(file: File, meta: BookMeta) {
        try {
            ZipFile(file).use { parse(it, meta) }
        } catch (_: Exception) {
        }
    }

    private class Item(val href: String, val type: String, val props: String)

    private fun parse(zip: ZipFile, meta: BookMeta) {
        val container = zip.getEntry("META-INF/container.xml") ?: return
        var opfPath: String? = null
        zip.getInputStream(container).use { s ->
            val p = newParser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                    opfPath = p.attr("full-path"); break
                }
            }
        }
        val opf = opfPath?.let { zip.getEntry(it) } ?: return
        val opfDir = opfPath!!.substringBeforeLast('/', "")

        val items = HashMap<String, Item>()
        var coverId: String? = null
        var calibreSeries: String? = null
        var calibreIndex: Float? = null
        var collection: String? = null
        var position: Float? = null

        zip.getInputStream(opf).use { s ->
            val p = newParser(s)
            var inMetadata = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG && local(p.name) == "metadata") inMetadata = false
                if (p.eventType != XmlPullParser.START_TAG) continue
                val name = local(p.name)
                when {
                    name == "metadata" -> inMetadata = true
                    name == "item" -> {
                        val id = p.attr("id") ?: continue
                        items[id] = Item(p.attr("href") ?: "", p.attr("media-type") ?: "", p.attr("properties") ?: "")
                    }
                    !inMetadata -> {}
                    name == "meta" -> {
                        val metaName = p.attr("name")
                        val content = p.attr("content")
                        when (metaName) {
                            "cover" -> coverId = content
                            "calibre:series" -> calibreSeries = content
                            "calibre:series_index" -> calibreIndex = content?.toFloatOrNull()
                        }
                        when (p.attr("property")) {
                            "belongs-to-collection" -> collection = p.nextText().trim()
                            "group-position" -> position = p.nextText().trim().toFloatOrNull()
                        }
                    }
                    name == "title" -> if (meta.title == null) meta.title = p.nextText().trim().ifEmpty { null }
                    name == "creator" -> {
                        val fileAs = p.attr("file-as")
                        val role = p.attr("role")
                        val value = p.nextText().trim()
                        if (role == null || role == "aut") {
                            val a = if (fileAs?.contains(',') == true) Author.parse(fileAs) else Author.parse(value)
                            if (value.isNotEmpty()) meta.authors.add(a)
                        }
                    }
                    name == "subject" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { meta.genres.add(it) }
                    name == "description" -> meta.annotation = Html.fromHtml(p.nextText(), Html.FROM_HTML_MODE_COMPACT)
                        .toString().trim().ifEmpty { null }
                    name == "language" -> meta.lang = p.nextText().trim().ifEmpty { null }
                }
            }
        }
        (calibreSeries ?: collection)?.let { meta.series.add(SeriesRef(it, calibreIndex ?: position)) }

        val cover = coverId?.let { items[it] }
            ?: items.values.firstOrNull { "cover-image" in it.props }
            ?: items.entries.firstOrNull { (id, it) ->
                it.type.startsWith("image/") && ("cover" in id.lowercase() || "cover" in it.href.lowercase())
            }?.value
        if (cover != null && cover.type.startsWith("image/")) {
            val path = resolve(opfDir, cover.href)
            zip.getEntry(path)?.let { e -> meta.coverBytes = zip.getInputStream(e).use { it.readBytes() } }
        }
    }

    private fun resolve(dir: String, href: String): String {
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in URLDecoder.decode(href, "UTF-8").split('/')) {
            when (seg) {
                ".." -> parts.removeLastOrNull()
                ".", "" -> {}
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}

object PdfCover {
    fun render(file: File, meta: BookMeta) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) return
                    r.openPage(0).use { page ->
                        val w = 360
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(100, 800)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        meta.coverBitmap = bmp
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}

/** Комиксы и манга: обложка — первая страница, серия — из ComicInfo.xml или имени папки. */
object CbzParser {
    private val chapterRe = Regex("""(?i)(?:chapter|ch\.?|глава|vol\.?|том)\s*(\d+(?:[.,]\d+)?)""")

    fun parseFile(file: File, meta: BookMeta) {
        try {
            ZipFile(file).use { zip ->
                zip.getEntry("ComicInfo.xml")?.let { e -> zip.getInputStream(e).use { parseComicInfo(it, meta) } }
                val first = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                    .minByOrNull { it.name.lowercase() }
                first?.let { e -> meta.coverBytes = zip.getInputStream(e).use { it.readBytes() } }
            }
        } catch (_: Exception) {
        }
        // «Chapter 9 - The Worst Possible Client_a334cb» → серия из папки, номер главы, название без хвоста
        val base = file.name.substringBeforeLast('.').replace(Regex("_[0-9a-f]{6}$"), "")
        if (meta.series.isEmpty()) {
            file.parentFile?.name?.let { folder ->
                meta.series.add(SeriesRef(folder, chapterRe.find(base)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()))
            }
        }
        if (meta.title == null) meta.title = base
    }

    private fun parseComicInfo(input: InputStream, meta: BookMeta) {
        var comicSeries: String? = null
        var comicNumber: Float? = null
        val p = newParser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "Title" -> meta.title = p.nextText().trim().ifEmpty { null }
                "Series" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { comicSeries = it }
                "Number" -> comicNumber = p.nextText().trim().toFloatOrNull()
                "Writer" -> p.nextText().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { meta.authors.add(Author.parse(it)) }
                "Genre" -> p.nextText().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { meta.genres.add(it) }
                "Summary" -> meta.annotation = p.nextText().trim().ifEmpty { null }
                "LanguageISO" -> meta.lang = p.nextText().trim().ifEmpty { null }
            }
        }
        comicSeries?.let { meta.series.add(SeriesRef(it, comicNumber)) }
    }
}
