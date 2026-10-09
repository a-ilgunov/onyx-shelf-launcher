package ru.efimov.booklib.data

import org.json.JSONArray
import org.json.JSONObject

data class Author(val first: String, val last: String) {
    /** «Брендон Сандерсон» — для карточек. */
    val display: String
        get() = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { NO_AUTHOR }

    /** «Сандерсон Брендон» — для списка авторов и сортировки. */
    val sortName: String
        get() = listOf(last, first).filter { it.isNotBlank() }.joinToString(" ").ifBlank { NO_AUTHOR }

    val key: String get() = sortName.lowercase()

    companion object {
        val NO_AUTHOR: String get() = L("Без автора", "Unknown author")

        /** «Brandon Sanderson» или «Sanderson, Brandon». */
        fun parse(raw: String): Author {
            val s = raw.trim().replace(Regex("\\s+"), " ")
            if (s.contains(',')) {
                return Author(s.substringAfter(',').trim(), s.substringBefore(',').trim())
            }
            val parts = s.split(' ')
            return if (parts.size == 1) Author("", s) else Author(parts.dropLast(1).joinToString(" "), parts.last())
        }
    }
}

/** Серия (цикл) и номер книги в ней. */
data class SeriesRef(val name: String, val index: Float?) {
    val key: String get() = name.lowercase().replace('ё', 'е').trim()
}

data class Book(
    val path: String,
    val format: String,
    val title: String,
    val authors: List<Author>,
    /** Все серии книги — от общего цикла к самому узкому («Мир Элдерлингов» → «Сага о Шуте и Убийце»). */
    val seriesList: List<SeriesRef>,
    val genres: List<String>,
    val annotation: String?,
    val lang: String?,
    val size: Long,
    val modified: Long,
    val cover: String?,
) {
    val authorsDisplay: String
        get() = if (authors.isEmpty()) Author.NO_AUTHOR else authors.joinToString(", ") { it.display }

    val firstAuthorSort: String
        get() = authors.firstOrNull()?.sortName ?: Author.NO_AUTHOR

    /** Главная серия для подписи и стопок — самая узкая с номером (или просто самая узкая). */
    val primarySeries: SeriesRef?
        get() = seriesList.lastOrNull { it.index != null } ?: seriesList.lastOrNull()

    val series: String? get() = primarySeries?.name
    val seriesIndex: Float? get() = primarySeries?.index

    fun indexIn(seriesKey: String): Float? = seriesList.firstOrNull { it.key == seriesKey }?.index

    val seriesLabel: String?
        get() = series?.let { s -> seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s }

    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("format", format)
        put("title", title)
        put("authors", JSONArray().apply {
            authors.forEach { put(JSONObject().put("f", it.first).put("l", it.last)) }
        })
        put("seriesList", JSONArray().apply {
            seriesList.forEach { s -> put(JSONObject().put("n", s.name).apply { s.index?.let { put("i", it.toDouble()) } }) }
        })
        put("genres", JSONArray(genres))
        annotation?.let { put("annotation", it) }
        lang?.let { put("lang", it) }
        put("size", size)
        put("modified", modified)
        cover?.let { put("cover", it) }
    }

    companion object {
        fun formatIndex(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

        fun fromJson(o: JSONObject): Book {
            val a = o.getJSONArray("authors")
            val g = o.getJSONArray("genres")
            return Book(
                path = o.getString("path"),
                format = o.getString("format"),
                title = o.getString("title"),
                authors = (0 until a.length()).map { a.getJSONObject(it).let { j -> Author(j.getString("f"), j.getString("l")) } },
                seriesList = o.optJSONArray("seriesList")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val j = arr.getJSONObject(i)
                        SeriesRef(j.getString("n"), if (j.has("i")) j.getDouble("i").toFloat() else null)
                    }
                } ?: emptyList(),
                genres = (0 until g.length()).map { g.getString(it) },
                annotation = o.optString("annotation").takeIf { it.isNotEmpty() },
                lang = o.optString("lang").takeIf { it.isNotEmpty() },
                size = o.getLong("size"),
                modified = o.getLong("modified"),
                cover = o.optString("cover").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** То, что удалось вытащить из файла при разборе. */
class BookMeta {
    var title: String? = null
    val authors = mutableListOf<Author>()
    val series = mutableListOf<SeriesRef>()
    val genres = mutableListOf<String>()
    var annotation: String? = null
    var lang: String? = null
    var coverBytes: ByteArray? = null
    var coverBitmap: android.graphics.Bitmap? = null
}

object Formats {
    private val mimes = linkedMapOf(
        "fb2" to "application/x-fictionbook+xml",
        "fb2.zip" to "application/x-zip-compressed-fb2",
        "epub" to "application/epub+zip",
        "pdf" to "application/pdf",
        "djvu" to "image/vnd.djvu",
        "djv" to "image/vnd.djvu",
        "mobi" to "application/x-mobipocket-ebook",
        "azw" to "application/vnd.amazon.ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "txt" to "text/plain",
        "rtf" to "application/rtf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "chm" to "application/vnd.ms-htmlhelp",
    )

    fun formatOf(name: String): String? {
        val n = name.lowercase()
        if (n.endsWith(".fb2.zip")) return "fb2.zip"
        return n.substringAfterLast('.', "").takeIf { it in mimes }
    }

    fun mime(format: String): String = mimes[format] ?: "*/*"
}
