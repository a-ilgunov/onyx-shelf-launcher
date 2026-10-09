package ru.efimov.booklib.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Уточнение серий по базе Фантлаба (api.fantlab.ru): находит произведение по названию и автору
 * и берёт его цепочку циклов с номерами. Работает только по кнопке, сеть в фоне не используется.
 */
/** Книга цикла по Фантлабу: номер, название, год. */
data class CycleWork(val index: Int, val title: String, val year: Int?)

/** Обложка издания из интернета: где взять и как подписать. */
data class OnlineCover(val url: String, val label: String)

class Fantlab {
    private val works = HashMap<Int, JSONObject?>()

    var lastOrigAuthor: String? = null
        private set

    /** Состав циклов, встреченных при сверке: ключ серии → основные книги по порядку. */
    val cycles = HashMap<String, List<CycleWork>>()

    /** Найти произведение на Фантлабе по названию и автору (строгое совпадение). */
    fun findWorkId(book: Book): Int? {
        val author = book.authors.firstOrNull() ?: return null
        val title = norm(book.title)
        if (title.isEmpty()) return null
        val q = URLEncoder.encode("${book.title} ${author.last}", "UTF-8")
        val matches = getArray("https://api.fantlab.ru/search-works?q=$q&page=1") ?: return null
        val authorKey = norm(author.last)
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            val names = listOf(m.optString("rusname"), m.optString("name")).map(::norm)
            val authors = norm(m.optString("autor_rusname") + " " + m.optString("autor_name") + " " + m.optString("all_autor_rusname"))
            if (title in names && (authorKey.isEmpty() || authorKey in authors)) return m.optInt("work_id").takeIf { it > 0 }
        }
        return null
    }

    /** Обложки всех изданий произведения на Фантлабе (в основном русские издания). */
    fun editionCovers(book: Book): Pair<List<OnlineCover>, String?> {
        val id = findWorkId(book) ?: return emptyList<OnlineCover>() to null
        val w = work(id) ?: return emptyList<OnlineCover>() to null
        val out = ArrayList<OnlineCover>()
        val blocks = w.optJSONObject("editions_blocks")
        blocks?.keys()?.forEach { k ->
            val list = blocks.optJSONObject(k)?.optJSONArray("list") ?: return@forEach
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                val eid = e.optInt("edition_id").takeIf { it > 0 } ?: continue
                val publisher = e.optString("publisher").replace(Regex("\\[/?pub[^\\]]*\\]"), "").substringBefore(',').trim()
                val year = e.optInt("year").takeIf { it > 0 }?.toString().orEmpty()
                out.add(OnlineCover("https://fantlab.ru/images/editions/big/$eid", listOf(publisher, year).filter { it.isNotEmpty() }.joinToString(", ")))
            }
        }
        // Оригинальные название и автор — для поиска англоязычных изданий
        val origAuthor = w.optJSONArray("authors")?.optJSONObject(0)?.optString("name_orig")?.takeIf { it.isNotBlank() }
        lastOrigAuthor = origAuthor
        return out to w.optString("work_name_orig").takeIf { it.isNotBlank() }
    }

    /** Цепочка циклов книги (от общего к узкому) или null, если книгу не нашли уверенно. */
    fun lookup(book: Book): List<SeriesRef>? {
        val author = book.authors.firstOrNull() ?: return null
        val title = norm(book.title)
        if (title.isEmpty()) return null
        val q = URLEncoder.encode("${book.title} ${author.last}", "UTF-8")
        val matches = getArray("https://api.fantlab.ru/search-works?q=$q&page=1") ?: return null
        val authorKey = norm(author.last)

        var workId: Int? = null
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            val names = listOf(m.optString("rusname"), m.optString("name")).map(::norm)
            val authors = norm(m.optString("autor_rusname") + " " + m.optString("autor_name") + " " + m.optString("all_autor_rusname"))
            if (title in names && (authorKey.isEmpty() || authorKey in authors)) {
                workId = m.optInt("work_id").takeIf { it > 0 }
                break
            }
        }
        val id = workId ?: return null
        val work = work(id) ?: return null

        // parents.cycles — списки цепочек «общий цикл → … → узкий»; берём самую длинную
        val chains = work.optJSONObject("parents")?.optJSONArray("cycles") ?: return emptyList()
        var best: JSONArray? = null
        for (i in 0 until chains.length()) {
            val c = chains.optJSONArray(i) ?: continue
            if (best == null || c.length() > best.length()) best = c
        }
        val chain = best ?: return emptyList()
        return (0 until chain.length()).mapNotNull { i ->
            val c = chain.getJSONObject(i)
            val name = c.optString("work_name").trim().ifEmpty { return@mapNotNull null }
            val ref = SeriesRef(name, positionIn(c.optInt("work_id"), id))
            cycles.getOrPut(ref.key) { cycleWorks(c.optInt("work_id")) }
            ref
        }
    }

    /** Основные книги цикла по порядку — чтобы показать, каких нет на устройстве. */
    private fun cycleWorks(cycleId: Int): List<CycleWork> {
        val children = work(cycleId)?.optJSONArray("children") ?: return emptyList()
        val out = ArrayList<CycleWork>()
        for (i in 0 until children.length()) {
            val ch = children.getJSONObject(i)
            val type = ch.optString("work_type").lowercase()
            if ("цикл" in type || "эпопея" in type || "серия" in type) continue
            // Тип строго «роман»/«повесть»: «часть романа» (зарубежные издания, поделённые на тома) не считаем
            if (ch.optInt("plus") == 1 || type !in MAIN_TYPES) continue
            val title = ch.optString("work_name").ifBlank { ch.optString("work_name_orig") }.trim()
            out.add(CycleWork(out.size + 1, title, ch.optInt("work_year").takeIf { it > 0 }))
        }
        return out
    }

    /** Номер произведения внутри цикла: порядок среди основных книг (без подциклов и рассказов). */
    private fun positionIn(cycleId: Int, workId: Int): Float? {
        val children = work(cycleId)?.optJSONArray("children") ?: return null
        var n = 0
        for (i in 0 until children.length()) {
            val ch = children.getJSONObject(i)
            val type = ch.optString("work_type").lowercase()
            if ("цикл" in type || "эпопея" in type || "серия" in type) continue
            // Только основные романы и повести: дополнительные вещи («plus») и рассказы номер не сдвигают
            // Тип строго «роман»/«повесть»: «часть романа» (зарубежные издания, поделённые на тома) не считаем
            if (ch.optInt("plus") == 1 || type !in MAIN_TYPES) continue
            n++
            if (ch.optInt("work_id") == workId) return n.toFloat()
        }
        return null
    }

    private fun work(id: Int): JSONObject? = works.getOrPut(id) {
        Thread.sleep(150) // бережно к сайту
        get("https://api.fantlab.ru/work/$id/extended")?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun getArray(url: String): JSONArray? {
        val text = get(url) ?: return null
        return runCatching {
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("matches")
        }.getOrNull()
    }

    fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "BooxLibrary/1.0 (personal e-reader library)")
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private val MAIN_TYPES = setOf("роман", "повесть")

        fun norm(s: String): String =
            s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
