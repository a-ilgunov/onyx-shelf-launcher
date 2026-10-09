package ru.efimov.booklib.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class SortMode(val label: String) {
    TITLE("Название"),
    AUTHOR("Автор"),
    SERIES("Серия"),
    ADDED("Дата добавления"),
    OPENED("Последнее открытие"),
    PROGRESS("Прогресс"),
    SIZE("Размер файла"),
}

/** Как показывать книги в библиотеке. */
enum class ViewMode(val label: String) {
    COVERS_HUGE("Крупные обложки"),
    COVERS_LARGE("Обычные обложки"),
    COVERS_SMALL("Мелкие обложки"),
    COVERS_ONLY("Только обложки"),
    LIST("Список"),
    DETAILED("Подробный список"),
    COMPACT("Компактный список"),
    ;

    val covers: Boolean get() = this == COVERS_HUGE || this == COVERS_LARGE || this == COVERS_SMALL || this == COVERS_ONLY
}

enum class ReadStatus(val label: String) {
    NEW("Не начатые"),
    READING("Читаю"),
    FINISHED("Прочитанные"),
}

/** Плитки, которые можно поставить на главный экран. */
enum class HomeWidget(val label: String) {
    FINISHED("Прочитано книг"),
    LIBRARY("Библиотека"),
    TODAY("Сегодня"),
    WEEK("За неделю"),
    YEAR("Прочитано в этом году"),
    READING("Читаю сейчас"),
    WANT("Хочу прочитать"),
    CONTINUE("Продолжить серию"),
    NONE("Пусто"),
}

/** Что показывать на полке внизу главного экрана. */
enum class HomeShelf(val label: String) {
    RECENT("Недавние"),
    CONTINUE("Продолжить серию"),
    WANT("Хочу прочитать"),
    NEW("Новые поступления"),
    NONE("Не показывать"),
}

private const val DEFAULT_SHELVES = """{"Хочу прочитать":[],"Избранное":[]}"""

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var readerPackage: String?
        get() = sp.getString("reader", null)
        set(v) = sp.edit().putString("reader", v).apply()

    /** Приложение, которым в последний раз открывали книги этого формата. */
    fun readerFor(format: String): String? = sp.getString("reader_$format", null)

    fun setReaderFor(format: String, pkg: String) = sp.edit().putString("reader_$format", pkg).apply()

    fun clearReadersPerFormat() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("reader_") }.forEach { e.remove(it) }
        e.apply()
    }

    var darkTheme: Boolean
        get() = sp.getBoolean("dark", false)
        set(v) = sp.edit().putBoolean("dark", v).apply()

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(sp.getString("view", null)!!) }
            .getOrElse { if (sp.getBoolean("grid", true)) ViewMode.COVERS_LARGE else ViewMode.LIST }
        set(v) = sp.edit().putString("view", v.name).apply()

    /** Обратный порядок сортировки. */
    var sortDesc: Boolean
        get() = sp.getBoolean("sort_desc", false)
        set(v) = sp.edit().putBoolean("sort_desc", v).apply()

    /** Фильтры: статус чтения (пусто — все), форматы и языки (пусто — все). */
    var filterStatus: Set<ReadStatus>
        get() = sp.getStringSet("f_status", emptySet())!!.mapNotNull { runCatching { ReadStatus.valueOf(it) }.getOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("f_status", v.map { it.name }.toSet()).apply()

    /** Показывать серии одной плиткой-стопкой. */
    var seriesStacks: Boolean
        get() = sp.getBoolean("stacks", false)
        set(v) = sp.edit().putBoolean("stacks", v).apply()

    /** Книги, скрытые из библиотеки (например, лишние копии). Файлы не удаляются. */
    var hiddenBooks: Set<String>
        get() = sp.getStringSet("hidden_books", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden_books", v).apply()

    /** Свои полки: название → пути книг, в порядке добавления. */
    var shelves: Map<String, List<String>>
        get() = try {
            val o = JSONObject(sp.getString("shelves", null) ?: DEFAULT_SHELVES)
            val m = LinkedHashMap<String, List<String>>()
            o.keys().forEach { k -> val a = o.getJSONArray(k); m[k] = (0 until a.length()).map { a.getString(it) } }
            m
        } catch (_: Exception) {
            linkedMapOf()
        }
        set(v) {
            val o = JSONObject()
            v.forEach { (k, list) -> o.put(k, org.json.JSONArray(list)) }
            sp.edit().putString("shelves", o.toString()).apply()
        }

    /** Четыре места для плиток: верхний ряд (1, 2) и нижний (3, 4). */
    var homeWidgets: List<HomeWidget>
        get() = (sp.getString("home_widgets", null) ?: "FINISHED,LIBRARY,NONE,NONE").split(',')
            .map { runCatching { HomeWidget.valueOf(it) }.getOrDefault(HomeWidget.NONE) }
            .let { (it + List(4) { HomeWidget.NONE }).take(4) }
        set(v) = sp.edit().putString("home_widgets", v.joinToString(",") { it.name }).apply()

    var homeShelf: HomeShelf
        get() = runCatching { HomeShelf.valueOf(sp.getString("home_shelf", null)!!) }.getOrDefault(HomeShelf.RECENT)
        set(v) = sp.edit().putString("home_shelf", v.name).apply()

    var filterFormats: Set<String>
        get() = sp.getStringSet("f_formats", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("f_formats", v).apply()

    var filterLangs: Set<String>
        get() = sp.getStringSet("f_langs", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("f_langs", v).apply()

    var sort: SortMode
        get() = runCatching { SortMode.valueOf(sp.getString("sort", null)!!) }.getOrDefault(SortMode.AUTHOR)
        set(v) = sp.edit().putString("sort", v.name).apply()

    private val _recent = MutableStateFlow(loadRecent())

    /** Путь → время последнего открытия. */
    val recent: StateFlow<Map<String, Long>> = _recent

    fun markOpened(path: String) {
        val map = _recent.value + (path to System.currentTimeMillis())
        // Храним только последние 200
        val trimmed = map.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        _recent.value = trimmed
        sp.edit().putString("recent", JSONObject(trimmed as Map<*, *>).toString()).apply()
    }

    /**
     * Книги, убранные из «Недавних»: путь → когда убрали. Книга снова появится там,
     * если её открыть позже этого момента.
     */
    var hiddenRecent: Map<String, Long>
        get() = try {
            val o = JSONObject(sp.getString("hidden_recent", "{}")!!)
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) {
            emptyMap()
        }
        set(v) = sp.edit().putString("hidden_recent", JSONObject(v as Map<*, *>).toString()).apply()

    private fun loadRecent(): Map<String, Long> = try {
        val o = JSONObject(sp.getString("recent", "{}")!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (_: Exception) {
        emptyMap()
    }
}
