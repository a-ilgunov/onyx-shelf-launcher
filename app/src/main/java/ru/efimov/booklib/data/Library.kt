package ru.efimov.booklib.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ScanState(val done: Int, val total: Int)

class Library(context: Context) {
    private val indexFile = File(context.filesDir, "index.json")
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val root: File = Environment.getExternalStorageDirectory()

    /** Свои обложки, выбранные вручную: путь к книге → путь к картинке. Переживают пересканирование. */
    private val customFile = File(context.filesDir, "custom_covers.json")
    private var custom: Map<String, String> = loadCustom()

    /** Серии, уточнённые через Фантлаб: путь к книге → цепочка циклов. Файлы книг не меняются. */
    private val seriesFile = File(context.filesDir, "series_overrides.json")
    private var seriesOverrides: Map<String, List<SeriesRef>> = loadSeriesOverrides()

    /** Состав циклов с Фантлаба: ключ серии → книги по порядку. */
    private val cyclesFile = File(context.filesDir, "cycles.json")
    private val _cycles = MutableStateFlow(loadCycles())
    val cycles: StateFlow<Map<String, List<CycleWork>>> = _cycles

    suspend fun saveCycles(found: Map<String, List<CycleWork>>) = withContext(Dispatchers.IO) {
        if (found.isEmpty()) return@withContext
        _cycles.value = _cycles.value + found
        val o = JSONObject()
        _cycles.value.forEach { (k, list) ->
            o.put(k, JSONArray().apply {
                list.forEach { w -> put(JSONObject().put("i", w.index).put("t", w.title).apply { w.year?.let { put("y", it) } }) }
            })
        }
        cyclesFile.writeText(o.toString())
    }

    private fun loadCycles(): Map<String, List<CycleWork>> = try {
        val o = JSONObject(cyclesFile.readText())
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                CycleWork(j.getInt("i"), j.getString("t"), if (j.has("y")) j.getInt("y") else null)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun applyOverrides(b: Book): Book {
        var r = b
        custom[b.path]?.let { r = r.copy(cover = it) }
        seriesOverrides[b.path]?.let { r = r.copy(seriesList = it) }
        return r
    }

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan

    suspend fun loadIndex() = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext
        try {
            val o = JSONObject(indexFile.readText())
            // Индекс старого формата не используем: книги разберутся заново
            if (o.optInt("version") != INDEX_VERSION) return@withContext
            val arr = o.getJSONArray("books")
            _books.value = (0 until arr.length()).map { Book.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
        }
    }

    /** Обходит память книги. Неизменившиеся файлы берутся из индекса, остальные разбираются заново. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        if (_scan.value != null) return@withContext
        _scan.value = ScanState(0, 0)
        try {
            val cached = _books.value.associateBy { it.path }
            val files = findBookFiles()
            val calibre = loadCalibre()
            val firstRun = cached.isEmpty()
            val result = ArrayList<Book>(files.size)
            _scan.value = ScanState(0, files.size)

            val parallel = Dispatchers.IO.limitedParallelism(4)
            for (chunk in files.chunked(24)) {
                val parsed = coroutineScope {
                    chunk.map { (file, format) ->
                        async(parallel) {
                            val old = cached[file.path]
                            if (old != null && old.modified == file.lastModified() && old.size == file.length()) old
                            else buildBook(file, format, calibre)
                        }
                    }.awaitAll()
                }
                result.addAll(parsed)
                _scan.value = ScanState(result.size, files.size)
                if (firstRun) _books.value = result.toList()
            }

            val final = result.map(::applyOverrides)
            _books.value = final
            saveIndex(final)
            cleanupCovers(final)
        } finally {
            _scan.value = null
        }
    }

    fun hasCustomCover(path: String): Boolean = path in custom

    val seriesFixedCount: Int get() = seriesOverrides.size

    /** Применить серии, найденные на Фантлабе. */
    suspend fun setSeriesOverrides(found: Map<String, List<SeriesRef>>) = withContext(Dispatchers.IO) {
        if (found.isEmpty()) return@withContext
        seriesOverrides = seriesOverrides + found
        saveSeriesOverrides()
        val list = _books.value.map { b -> found[b.path]?.let { b.copy(seriesList = it) } ?: b }
        _books.value = list
        saveIndex(list)
    }

    /** Сбросить все уточнения — вернуть серии из самих файлов. */
    suspend fun clearSeriesOverrides() = withContext(Dispatchers.IO) {
        val paths = seriesOverrides.keys
        seriesOverrides = emptyMap()
        saveSeriesOverrides()
        val calibre = loadCalibre()
        val list = _books.value.map { b ->
            if (b.path !in paths) b else {
                val f = File(b.path)
                Formats.formatOf(f.name)?.let { applyOverrides(buildBook(f, it, calibre)) } ?: b
            }
        }
        _books.value = list
        saveIndex(list)
    }

    private fun loadSeriesOverrides(): Map<String, List<SeriesRef>> = try {
        val o = JSONObject(seriesFile.readText())
        o.keys().asSequence().associateWith { k ->
            val arr = o.getJSONArray(k)
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                SeriesRef(j.getString("n"), if (j.has("i")) j.getDouble("i").toFloat() else null)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveSeriesOverrides() {
        val o = JSONObject()
        seriesOverrides.forEach { (k, v) ->
            o.put(k, JSONArray().apply { v.forEach { s -> put(JSONObject().put("n", s.name).apply { s.index?.let { put("i", it.toDouble()) } }) } })
        }
        seriesFile.writeText(o.toString())
    }

    /** Поставить книге свою обложку из картинки на устройстве. */
    suspend fun setCustomCover(book: Book, image: File): Boolean = withContext(Dispatchers.IO) {
        val bmp = try {
            decodeScaled(image.readBytes(), 360)
        } catch (_: Exception) {
            null
        } ?: return@withContext false
        // Новое имя файла — чтобы кэш обложек не показал старую картинку
        val out = File(coverDir, "custom_${md5(book.path)}_${System.currentTimeMillis()}.jpg")
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } finally {
            bmp.recycle()
        }
        custom[book.path]?.let { File(it).delete() }
        custom = custom + (book.path to out.path)
        saveCustom()
        replaceBook(book.path) { it.copy(cover = out.path) }
        true
    }

    /** Вернуть обложку из самого файла книги. */
    suspend fun resetCover(book: Book) = withContext(Dispatchers.IO) {
        custom[book.path]?.let { File(it).delete() }
        custom = custom - book.path
        saveCustom()
        val f = File(book.path)
        val format = Formats.formatOf(f.name) ?: return@withContext
        val rebuilt = buildBook(f, format, loadCalibre())
        replaceBook(book.path) { applyOverrides(rebuilt) }
    }

    private fun replaceBook(path: String, change: (Book) -> Book) {
        val list = _books.value.map { if (it.path == path) change(it) else it }
        _books.value = list
        saveIndex(list)
    }

    private fun loadCustom(): Map<String, String> = try {
        val o = JSONObject(customFile.readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveCustom() {
        customFile.writeText(JSONObject(custom as Map<*, *>).toString())
    }

    /**
     * Картинки на устройстве для выбора обложки: сначала из папки книги,
     * потом из папки «Обложки», потом все остальные — от новых к старым.
     */
    suspend fun findImages(book: Book): List<File> = withContext(Dispatchers.IO) {
        val exts = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val all = ArrayList<File>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || (!dir.name.startsWith('.') && dir.name.lowercase() !in setOf("android", "lost+found")) }
            .forEach { f ->
                if (f.isFile && !f.name.startsWith('.') && f.extension.lowercase() in exts && f.length() > 4096) all.add(f)
            }
        val bookDir = File(book.path).parentFile
        // Папка «Обложки» в корне памяти — вместе со всеми подпапками (по авторам и т.п.)
        val coversDir = root.listFiles()?.firstOrNull { it.isDirectory && it.name.lowercase() == "обложки" }?.path?.plus("/")
        fun rank(f: File) = when {
            f.parentFile == bookDir -> 0
            coversDir != null && f.path.startsWith(coversDir) -> 1
            else -> 2
        }
        all.sortedWith(compareBy<File> { rank(it) }.thenByDescending { it.lastModified() })
    }

    private fun findBookFiles(): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || (!dir.name.startsWith('.') && dir.name.lowercase() !in skipDirs) }
            .forEach { f ->
                // В корне памяти лежат служебные .txt (aliases.utf8.txt и т.п.)
                if (f.isFile && !(f.parentFile == root && f.name.endsWith(".txt"))) {
                    Formats.formatOf(f.name)?.let { out.add(f to it) }
                }
            }
        return out
    }

    private fun buildBook(file: File, format: String, calibre: Map<String, CalibreEntry>): Book {
        val meta = BookMeta()
        when (format) {
            "fb2", "fb2.zip" -> Fb2Parser.parseFile(file, format, meta)
            "epub" -> EpubParser.parseFile(file, meta)
            "pdf" -> PdfCover.render(file, meta)
            "cbz" -> CbzParser.parseFile(file, meta)
        }

        val baseName = file.name.removeSuffix(".zip").substringBeforeLast('.')
        var titleFromName = false
        if (meta.title.isNullOrBlank()) {
            titleFromName = true
            // «Автор - Название» в имени файла
            val parts = baseName.split(" - ", limit = 2)
            if (parts.size == 2 && meta.authors.isEmpty()) {
                meta.authors.add(Author.parse(parts[0]))
                meta.title = parts[1].trim()
            } else {
                meta.title = baseName
            }
        }

        // Данные Calibre дополняют то, чего нет в самом файле
        calibre[file.path.removePrefix(root.path + "/")]?.let { c ->
            if (titleFromName && c.title != null) meta.title = c.title
            if ((titleFromName || meta.authors.isEmpty()) && c.authors.isNotEmpty()) {
                meta.authors.clear(); meta.authors.addAll(c.authors)
            }
            if (meta.series.isEmpty() && c.series != null) meta.series.add(SeriesRef(c.series, c.seriesIndex))
            if (meta.genres.isEmpty()) meta.genres.addAll(c.tags)
            if (meta.annotation == null) meta.annotation = c.comments
        }

        return Book(
            path = file.path,
            format = format,
            title = meta.title!!,
            authors = meta.authors.distinctBy { it.key },
            seriesList = meta.series.toList(),
            genres = meta.genres,
            annotation = meta.annotation,
            lang = meta.lang,
            size = file.length(),
            modified = file.lastModified(),
            cover = saveCover(file.path, meta),
        )
    }

    private fun saveCover(path: String, meta: BookMeta): String? {
        val out = File(coverDir, md5(path) + ".jpg")
        val bmp = meta.coverBitmap ?: meta.coverBytes?.let { decodeScaled(it, 360) } ?: run {
            out.delete(); return null
        }
        return try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            out.path
        } catch (_: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun decodeScaled(bytes: ByteArray, targetW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (bmp.width <= targetW) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, targetW, bmp.height * targetW / bmp.width, true)
        if (scaled != bmp) bmp.recycle()
        return scaled
    }

    private fun saveIndex(books: List<Book>) {
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(
            JSONObject().put("version", INDEX_VERSION).put("books", JSONArray().apply { books.forEach { put(it.toJson()) } }).toString()
        )
        tmp.renameTo(indexFile)
    }

    private fun cleanupCovers(books: List<Book>) {
        val used = books.mapNotNull { it.cover }.toSet()
        coverDir.listFiles()?.forEach { if (it.path !in used) it.delete() }
    }

    private class CalibreEntry(
        val title: String?,
        val authors: List<Author>,
        val series: String?,
        val seriesIndex: Float?,
        val tags: List<String>,
        val comments: String?,
    )

    private fun loadCalibre(): Map<String, CalibreEntry> {
        val f = File(root, "metadata.calibre")
        if (!f.exists()) return emptyMap()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).associate { i ->
                val o: JSONObject = arr.getJSONObject(i)
                val authors = o.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    .orEmpty().filter { it != "Unknown" && it.isNotBlank() }.map { Author.parse(it) }
                val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                o.getString("lpath") to CalibreEntry(
                    title = o.optString("title").takeIf { it.isNotBlank() && it != "Unknown" && it != "null" },
                    authors = authors,
                    series = o.optString("series").takeIf { it.isNotBlank() && it != "null" },
                    seriesIndex = if (o.isNull("series_index")) null else o.optDouble("series_index").toFloat(),
                    tags = tags,
                    comments = o.optString("comments").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim() },
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        /** Растёт, когда меняется то, что извлекается из файлов (v2 — все серии книги). */
        private const val INDEX_VERSION = 2

        /** Системные и служебные папки, где книг не бывает. */
        private val skipDirs = setOf(
            "android", "dcim", "music", "movies", "pictures", "ringtones", "alarms", "notifications",
            "podcasts", "voicerecord", "screensaver", "dicts", "fonts", "обложки", "lost+found",
            // Push не пропускаем: туда приходят книги, отправленные через BooxDrop и push.boox.com
            "oreaderx", "pocketbook",
        )

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
