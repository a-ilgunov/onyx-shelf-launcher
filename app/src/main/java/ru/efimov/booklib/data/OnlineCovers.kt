package ru.efimov.booklib.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Обложки изданий из интернета: Фантлаб (русские издания) и Open Library (зарубежные).
 * Только по запросу из экрана выбора обложки.
 */
object OnlineCovers {
    fun find(book: Book): List<OnlineCover> {
        val fantlab = Fantlab()
        val (fl, origTitle) = runCatching { fantlab.editionCovers(book) }.getOrDefault(emptyList<OnlineCover>() to null)
        val ol = runCatching { openLibrary(origTitle, fantlab.lastOrigAuthor, fantlab) }.getOrDefault(emptyList())
        return (fl + ol).distinctBy { it.url }
    }

    private fun openLibrary(title: String?, author: String?, http: Fantlab): List<OnlineCover> {
        if (title == null) return emptyList()
        val q = buildString {
            append("https://openlibrary.org/search.json?fields=key,title&limit=1&title=").append(URLEncoder.encode(title, "UTF-8"))
            if (author != null) append("&author=").append(URLEncoder.encode(author, "UTF-8"))
        }
        val key = JSONObject(http.get(q) ?: return emptyList()).optJSONArray("docs")?.optJSONObject(0)?.optString("key")
            ?.takeIf { it.startsWith("/works/") } ?: return emptyList()
        val entries = JSONObject(http.get("https://openlibrary.org$key/editions.json?limit=60") ?: return emptyList()).optJSONArray("entries")
            ?: return emptyList()
        val out = ArrayList<OnlineCover>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val covers = e.optJSONArray("covers") ?: continue
            val publisher = e.optJSONArray("publishers")?.optString(0).orEmpty()
            val year = Regex("\\d{4}").find(e.optString("publish_date"))?.value.orEmpty()
            for (j in 0 until covers.length()) {
                val id = covers.optLong(j).takeIf { it > 0 } ?: continue
                out.add(OnlineCover("https://covers.openlibrary.org/b/id/$id-L.jpg?default=false", listOf(publisher, year).filter { it.isNotEmpty() }.joinToString(", ")))
            }
        }
        return out
    }

    /** Скачать картинку в кэш (повторно не качает). null — не получилось. */
    fun download(ctx: Context, url: String): File? {
        val dir = File(ctx.cacheDir, "netcovers").apply { mkdirs() }
        val name = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val out = File(dir, "$name.jpg")
        if (out.exists() && out.length() > 1024) return out
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "BooxLibrary/1.0 (personal e-reader library)")
            try {
                if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) return null
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            } finally {
                c.disconnect()
            }
            out.takeIf { it.length() > 1024 } ?: run { out.delete(); null }
        } catch (_: Exception) {
            out.delete()
            null
        }
    }
}
