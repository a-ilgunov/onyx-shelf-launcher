package ru.efimov.booklib.ui

import ru.efimov.booklib.data.L
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.efimov.booklib.data.Book
import ru.efimov.booklib.data.Formats
import java.io.File

data class ReaderApp(val pkg: String, val label: String)

object Readers {
    /** Приложения, которые умеют открывать книги (FB2, EPUB или PDF) — для выбора в настройках. */
    fun list(ctx: Context): List<ReaderApp> =
        listOf("fb2", "epub", "pdf")
            .flatMap { fmt -> query(ctx, viewIntent(File("/sdcard/a.$fmt"), Formats.mime(fmt))) }
            .distinctBy { it.pkg }

    /** Приложения, которые откроют именно эту книгу. */
    fun appsFor(ctx: Context, book: Book): List<ReaderApp> = query(ctx, intentFor(book))

    fun label(ctx: Context, pkg: String?): String {
        if (pkg == null) return L("Запоминать последний выбор", "Remember last choice")
        val pm = ctx.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /**
     * Открывает книгу в указанном приложении по file:// — так читалка ONYX видит настоящий путь
     * и продолжает с того же места, что и из родной библиотеки. false — приложение не смогло.
     */
    fun launch(ctx: Context, book: Book, pkg: String): Boolean {
        val intent = intentFor(book).setPackage(pkg)
        if (intent.resolveActivity(ctx.packageManager) == null) return false
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun query(ctx: Context, intent: Intent): List<ReaderApp> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { pkg ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                ReaderApp(pkg, label)
            }
    }

    private fun intentFor(book: Book) = viewIntent(File(book.path), Formats.mime(book.format))

    private fun viewIntent(file: File, mime: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.fromFile(file), mime)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
