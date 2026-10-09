package ru.efimov.booklib.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Прогресс чтения из системной базы ONYX: её заполняют читалки ONYX и OReaderX. */
data class Progress(val current: Int, val total: Int, val lastAccess: Long, val status: Int, val hash: String? = null) {
    val fraction: Float get() = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = status == 2 || (total > 0 && current >= total)
    val percent: Int get() = (fraction * 100).toInt()
    val label: String get() = if (finished) L("Прочитано", "Finished") else "$percent%"
}

object OnyxReading {
    val uri: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    val statsUri: Uri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")

    /**
     * «Закончено» из статистики чтения ONYX. Готового числа ONYX не хранит: его экран статистики
     * каждый раз задаёт базе этот же запрос (книги с событием «дочитано», тип 6), поэтому цифры совпадают.
     */
    /** Общее время чтения в часах — как «Длительность» на экране статистики ONYX (листание и закрытие книги, типы 1 и 5). */
    fun totalHours(ctx: Context): Double? = try {
        ctx.contentResolver.query(statsUri, arrayOf("SUM(durationTime) AS c"), "type IN (1, 5)", null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) / 3_600_000.0 else null }
    } catch (_: Exception) {
        null
    }

    /** Сколько времени (мс) читали конкретную книгу — по её отпечатку в статистике ONYX. */
    fun readingTime(ctx: Context, hash: String): Long? = try {
        ctx.contentResolver.query(statsUri, arrayOf("SUM(durationTime) AS c"), "type IN (1, 5) AND md5=?", arrayOf(hash), null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }

    /** Время чтения (мс) с указанного момента — для плиток «Сегодня» и «За неделю». */
    fun readingTimeSince(ctx: Context, since: Long): Long? = try {
        ctx.contentResolver.query(statsUri, arrayOf("SUM(durationTime) AS c"), "type IN (1, 5) AND eventTime>?", arrayOf(since.toString()), null)
            ?.use { c -> if (c.moveToFirst()) (if (c.isNull(0)) 0L else c.getLong(0)) else null }
    } catch (_: Exception) {
        null
    }

    /** Сколько книг дочитано с указанного момента (события «дочитано» в статистике ONYX). */
    fun finishedSince(ctx: Context, since: Long): Int? = try {
        ctx.contentResolver.query(statsUri, arrayOf("COUNT(DISTINCT docId) AS c"), "type=6 AND eventTime>=?", arrayOf(since.toString()), null)
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    } catch (_: Exception) {
        null
    }

    fun finishedCount(ctx: Context): Int? = try {
        ctx.contentResolver.query(statsUri, arrayOf("COUNT(DISTINCT docId) AS c"), "type=6", null, null)
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    } catch (_: Exception) {
        null
    }

    /** Путь к файлу → прогресс. Если база недоступна, возвращает пустую карту. */
    fun load(ctx: Context): Map<String, Progress> = try {
        ctx.contentResolver.query(
            uri, arrayOf("nativeAbsolutePath", "progress", "lastAccess", "readingStatus", "hashTag"), null, null, null,
        )?.use { c ->
            val out = HashMap<String, Progress>()
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                // «173/746»
                val parts = c.getString(1)?.split('/')
                val cur = parts?.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val total = parts?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val last = if (c.isNull(2)) 0L else c.getLong(2)
                val status = if (c.isNull(3)) 0 else c.getInt(3)
                if (total > 0 || last > 0) out[path] = Progress(cur, total, last, status, c.getString(4))
            }
            out
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

data class AppEntry(val pkg: String, val activity: String, val label: String, val icon: ImageBitmap)

object Apps {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = java.text.Collator.getInstance(java.util.Locale("ru"))
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val d = ri.loadIcon(pm)
                val size = 144
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                AppEntry(ri.activityInfo.packageName, ri.activityInfo.name, ri.loadLabel(pm).toString(), bmp.asImageBitmap())
            }
            .sortedWith(compareBy(collator) { it.label })
    }
}
