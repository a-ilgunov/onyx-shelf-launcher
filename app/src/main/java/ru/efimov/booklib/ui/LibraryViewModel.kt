package ru.efimov.booklib.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.efimov.booklib.data.AppEntry
import ru.efimov.booklib.data.Apps
import ru.efimov.booklib.data.Book
import ru.efimov.booklib.data.Fantlab
import ru.efimov.booklib.data.SeriesRef
import ru.efimov.booklib.data.Library
import ru.efimov.booklib.data.OnyxReading
import ru.efimov.booklib.data.Prefs
import ru.efimov.booklib.data.Progress
import ru.efimov.booklib.data.ReadStatus
import ru.efimov.booklib.data.SortMode
import ru.efimov.booklib.data.ViewMode
import java.io.File

/** Разделы нижней панели, как у лаунчера ONYX. */
enum class Section(val label: String) {
    LIBRARY("Библиотека"),
    APPS("Приложения"),
    STORAGE("Память"),
    SETTINGS("Настройки"),
}

enum class Tab(val label: String) {
    ALL("Все книги"),
    AUTHORS("Авторы"),
    SERIES("Серии"),
    GENRES("Жанры"),
    RECENT("Недавние"),
    SHELVES("Полки"),
}

/** Открытая группа: конкретный автор, серия или жанр. */
data class Group(val tab: Tab, val key: String, val title: String)

/** Слои поверх экрана. Рисуются без оконной анимации, в отличие от системных диалогов. */
sealed class Overlay {
    data class Details(val book: Book) : Overlay()
    data class CoverPicker(val book: Book) : Overlay()
    data object ViewOptions : Overlay()
    data class NewShelf(val book: Book?) : Overlay()
    data object Duplicates : Overlay()
    data object HomeEditor : Overlay()
    /** [under] — слой, поверх которого открыт выбор (например, настройка главного экрана). */
    class Choice(val title: String, val options: List<String>, val selected: Int, val under: Overlay? = null, val onSelect: (Int) -> Unit) : Overlay()
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val library = Library(app)
    val prefs = Prefs(app)
    val storageRoot: File = Environment.getExternalStorageDirectory()

    var section by mutableStateOf(Section.LIBRARY)
    var tab by mutableStateOf(Tab.ALL)
        private set
    var group by mutableStateOf<Group?>(null)
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var viewMode by mutableStateOf(prefs.viewMode)
        private set
    var sortDesc by mutableStateOf(prefs.sortDesc)
        private set
    var filterStatus by mutableStateOf(prefs.filterStatus)
        private set
    var filterFormats by mutableStateOf(prefs.filterFormats)
        private set
    var filterLangs by mutableStateOf(prefs.filterLangs)
        private set

    var seriesStacks by mutableStateOf(prefs.seriesStacks)
        private set
    var hiddenBooks by mutableStateOf(prefs.hiddenBooks)
        private set
    var shelves by mutableStateOf(prefs.shelves)
        private set

    /** Ход уточнения серий через Фантлаб: null — не идёт. */
    var seriesFix by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val filtersActive: Boolean
        get() = filterStatus.isNotEmpty() || filterFormats.isNotEmpty() || filterLangs.isNotEmpty()
    var sort by mutableStateOf(prefs.sort)
        private set
    var readerPackage by mutableStateOf(prefs.readerPackage)
        private set
    var overlay by mutableStateOf<Overlay?>(null)

    /** Номер страницы для каждого экрана: при возврате назад оказываемся там же. */
    private val pages = mutableStateMapOf<String, Int>()

    /** Прогресс чтения из базы ONYX, обновляется при каждом возвращении на экран. */
    var reading by mutableStateOf<Map<String, Progress>>(emptyMap())
        private set
    /** Сколько книг закончено — по статистике чтения ONYX (null, если она недоступна). */
    var finishedTotal by mutableStateOf<Int?>(null)
        private set
    /** Время чтения сегодня и с понедельника, книги, дочитанные в этом году — для плиток главной. */
    var todayMs by mutableStateOf<Long?>(null)
        private set
    var weekMs by mutableStateOf<Long?>(null)
        private set
    var yearFinished by mutableStateOf<Int?>(null)
        private set

    var homeWidgets by mutableStateOf(prefs.homeWidgets)
        private set
    var homeShelf by mutableStateOf(prefs.homeShelf)
        private set

    fun setHomeWidget(slot: Int, w: ru.efimov.booklib.data.HomeWidget) {
        homeWidgets = homeWidgets.toMutableList().also { it[slot] = w }
        prefs.homeWidgets = homeWidgets
    }

    fun chooseHomeShelf(s: ru.efimov.booklib.data.HomeShelf) {
        homeShelf = s
        prefs.homeShelf = s
    }

    /** Общее время чтения в часах — по статистике ONYX. */
    var totalHours by mutableStateOf<Double?>(null)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var storageDir by mutableStateOf(storageRoot)

    private var loaded = false

    /** Иконки приложений рисуются один раз и обновляются, только когда программы ставят или удаляют. */
    private var appsDirty = true
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            if (section == Section.APPS) reloadApps()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        app.registerReceiver(packageReceiver, filter)
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(packageReceiver)
        getApplication<Application>().contentResolver.unregisterContentObserver(readingObserver)
    }

    /** Ключ страниц основного списка библиотеки (страница 0 — главная). */
    val mainKey: String get() = "lib:${tab.name}:${sort.name}:$sortDesc"

    fun page(key: String): Int = pages[key] ?: 0
    fun setPage(key: String, p: Int) {
        pages[key] = p
    }

    /** Вызывается из onResume. */
    fun onResume() {
        if (!loaded) {
            loaded = true
            try {
                val cr = getApplication<Application>().contentResolver
                cr.registerContentObserver(OnyxReading.uri, true, readingObserver)
                cr.registerContentObserver(OnyxReading.statsUri, true, readingObserver)
            } catch (_: Exception) {
            }
            viewModelScope.launch {
                library.loadIndex()
                library.rescan()
            }
        }
        refreshReading()
        // Читалка записывает страницу в базу ONYX уже после того, как мы вернулись на экран.
        // Перечитываем ещё дважды; если ничего не изменилось, экран не перерисуется.
        delayedRefresh?.cancel()
        delayedRefresh = viewModelScope.launch {
            delay(1500); refreshReading()
            delay(2500); refreshReading()
        }
    }

    private var delayedRefresh: Job? = null

    /** Новые значения присваиваются, только если отличаются, — иначе Compose ничего не перерисует. */
    private fun refreshReading() {
        viewModelScope.launch {
            val ctx: Application = getApplication()
            val r = withContext(Dispatchers.IO) { OnyxReading.load(ctx) }
            val f = withContext(Dispatchers.IO) { OnyxReading.finishedCount(ctx) }
            val h = withContext(Dispatchers.IO) { OnyxReading.totalHours(ctx) }
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val weekStart = (cal.clone() as java.util.Calendar).apply {
                firstDayOfWeek = java.util.Calendar.MONDAY
                set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                if (timeInMillis > dayStart) add(java.util.Calendar.WEEK_OF_YEAR, -1)
            }.timeInMillis
            val yearStart = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            val t = withContext(Dispatchers.IO) { OnyxReading.readingTimeSince(ctx, dayStart) }
            val wk = withContext(Dispatchers.IO) { OnyxReading.readingTimeSince(ctx, weekStart) }
            val yr = withContext(Dispatchers.IO) { OnyxReading.finishedSince(ctx, yearStart) }
            if (t != todayMs) todayMs = t
            if (wk != weekMs) weekMs = wk
            if (yr != yearFinished) yearFinished = yr
            if (r != reading) reading = r
            if (f != finishedTotal) finishedTotal = f
            if (h != totalHours) totalHours = h
        }
    }

    /** База ONYX сама сообщает об изменениях — без опроса по таймеру. */
    private val readingObserver by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshReading()
        }
    }

    fun reloadApps() {
        if (!appsDirty) return
        appsDirty = false
        viewModelScope.launch { apps = withContext(Dispatchers.IO) { Apps.load(getApplication()) } }
    }

    fun rescan() {
        viewModelScope.launch { library.rescan() }
    }

    fun openSection(s: Section) {
        section = s
        if (s == Section.APPS) reloadApps()
    }

    /** Смена раздела библиотеки сразу открывает его первую страницу, минуя главную. */
    fun selectTab(t: Tab) {
        tab = t
        group = null
        setPage(mainKey, 1)
    }

    /** [keepTab] — открыть группу, не меняя раздел (стопка серии из «Всех книг»). */
    fun openGroup(g: Group, keepTab: Boolean = false) {
        section = Section.LIBRARY
        if (!keepTab) tab = g.tab
        group = g
    }

    fun setView(v: ViewMode) {
        viewMode = v
        prefs.viewMode = v
    }

    fun toggleSortDesc() {
        sortDesc = !sortDesc
        prefs.sortDesc = sortDesc
    }

    fun toggleStatus(s: ReadStatus) {
        filterStatus = if (s in filterStatus) filterStatus - s else filterStatus + s
        prefs.filterStatus = filterStatus
    }

    fun toggleFormat(f: String) {
        filterFormats = if (f in filterFormats) filterFormats - f else filterFormats + f
        prefs.filterFormats = filterFormats
    }

    fun toggleLang(l: String) {
        filterLangs = if (l in filterLangs) filterLangs - l else filterLangs + l
        prefs.filterLangs = filterLangs
    }

    fun toggleDark() {
        Palette.dark = !Palette.dark
        prefs.darkTheme = Palette.dark
    }

    fun toggleStacks() {
        seriesStacks = !seriesStacks
        prefs.seriesStacks = seriesStacks
    }

    // ----- Скрытые книги (дубликаты) -----

    fun hideBook(book: Book) {
        hiddenBooks = hiddenBooks + book.path
        prefs.hiddenBooks = hiddenBooks
    }

    fun unhideAll() {
        hiddenBooks = emptySet()
        prefs.hiddenBooks = hiddenBooks
    }

    // ----- Свои полки -----

    fun toggleOnShelf(shelf: String, book: Book) {
        val list = shelves[shelf].orEmpty()
        shelves = LinkedHashMap(shelves).apply { put(shelf, if (book.path in list) list - book.path else list + book.path) }
        prefs.shelves = shelves
    }

    fun createShelf(name: String, book: Book?) {
        val n = name.trim()
        if (n.isEmpty()) return
        shelves = LinkedHashMap(shelves).apply { putIfAbsent(n, emptyList()) }
        prefs.shelves = shelves
        if (book != null && book.path !in shelves[n].orEmpty()) toggleOnShelf(n, book)
    }

    fun deleteShelf(name: String) {
        shelves = LinkedHashMap(shelves).apply { remove(name) }
        prefs.shelves = shelves
    }

    /** Выбор полок для книги: отмеченные — где она уже лежит; последний пункт — новая полка. */
    fun chooseShelf(book: Book) {
        val names = shelves.keys.toList()
        overlay = Overlay.Choice(
            "На полку",
            names.map { if (book.path in shelves[it].orEmpty()) "✓  $it" else it } + "+ Новая полка…",
            -1,
        ) { i -> if (i < names.size) toggleOnShelf(names[i], book) else overlay = Overlay.NewShelf(book) }
    }

    // ----- Серии через Фантлаб -----

    fun fixSeriesOnline() {
        if (seriesFix != null) return
        val ctx: Application = getApplication()
        if (!isOnline()) {
            Toast.makeText(ctx, "Нет интернета — включите Wi-Fi", Toast.LENGTH_LONG).show()
            return
        }
        // Комиксы и документы Фантлабу неизвестны — проверяем книги с автором
        val books = library.books.value.filter { it.authors.isNotEmpty() && it.format !in setOf("cbz", "cbr", "pdf", "docx", "doc", "txt") }
        seriesFix = 0 to books.size
        viewModelScope.launch {
            val found = HashMap<String, List<SeriesRef>>()
            val api = Fantlab()
            withContext(Dispatchers.IO) {
                books.forEachIndexed { i, b ->
                    api.lookup(b)?.takeIf { it.isNotEmpty() }?.let { chain -> if (chain != b.seriesList) found[b.path] = chain }
                    withContext(Dispatchers.Main) { seriesFix = (i + 1) to books.size }
                }
            }
            library.setSeriesOverrides(found)
            // Полный состав циклов — чтобы показывать, каких книг серии нет на устройстве
            library.saveCycles(api.cycles)
            seriesFix = null
            Toast.makeText(ctx, if (found.isEmpty()) "Серии уже в порядке" else "Уточнены серии у ${booksWord(found.size)}", Toast.LENGTH_LONG).show()
        }
    }

    fun isOnline(): Boolean =
        getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null

    /** Скачать обложку издания и поставить её книге. */
    fun setCoverFromUrl(book: Book, url: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { ru.efimov.booklib.data.OnlineCovers.download(getApplication(), url) }
            if (file == null) {
                Toast.makeText(getApplication(), "Не удалось скачать обложку", Toast.LENGTH_SHORT).show()
            } else {
                setCover(book, file)
            }
        }
    }

    fun clearSeriesFixes() {
        viewModelScope.launch { library.clearSeriesOverrides() }
    }

    fun resetFilters() {
        filterStatus = emptySet(); filterFormats = emptySet(); filterLangs = emptySet()
        prefs.filterStatus = filterStatus; prefs.filterFormats = filterFormats; prefs.filterLangs = filterLangs
    }

    fun statusOf(book: Book): ReadStatus {
        val p = reading[book.path] ?: return ReadStatus.NEW
        return when {
            p.finished -> ReadStatus.FINISHED
            p.current > 1 -> ReadStatus.READING
            else -> ReadStatus.NEW
        }
    }

    /** Книга проходит фильтры (поиск проверяется отдельно). */
    fun passesFilters(book: Book): Boolean =
        (filterStatus.isEmpty() || statusOf(book) in filterStatus) &&
            (filterFormats.isEmpty() || book.format in filterFormats) &&
            (filterLangs.isEmpty() || langOf(book) in filterLangs)

    fun setSortMode(s: SortMode) {
        val onHome = page(mainKey) == 0
        sort = s
        prefs.sort = s
        if (!onHome) setPage(mainKey, 1)
    }

    /** Выбор в настройках: конкретное приложение для всех книг или «запоминать последний выбор». */
    fun setReader(pkg: String?) {
        readerPackage = pkg
        prefs.readerPackage = pkg
        prefs.clearReadersPerFormat()
    }

    /**
     * Открыть книгу. Приложение берётся то, которым в последний раз открывали этот формат
     * (или выбранное в настройках); спрашиваем, только если его ещё нет. [choose] — «Открыть с помощью…».
     */
    fun openBook(book: Book, choose: Boolean = false) {
        val ctx: Application = getApplication()
        if (!choose) {
            val pkg = readerPackage ?: prefs.readerFor(book.format)
            if (pkg != null && Readers.launch(ctx, book, pkg)) {
                prefs.markOpened(book.path)
                return
            }
        }
        val apps = Readers.appsFor(ctx, book)
        val launchWith = { app: ReaderApp ->
            prefs.setReaderFor(book.format, app.pkg)
            if (Readers.launch(ctx, book, app.pkg)) prefs.markOpened(book.path)
        }
        when {
            apps.isEmpty() -> Toast.makeText(ctx, "Нет приложения для формата ${book.format.uppercase()}", Toast.LENGTH_LONG).show()
            apps.size == 1 && !choose -> launchWith(apps[0])
            else -> overlay = Overlay.Choice(
                if (choose) "Открыть с помощью" else "Чем открывать ${book.format.uppercase()}",
                apps.map { it.label },
                apps.indexOfFirst { it.pkg == prefs.readerFor(book.format) },
            ) { launchWith(apps[it]) }
        }
    }

    fun closeSearch() {
        searchOpen = false
        query = ""
    }

    var hiddenRecent by mutableStateOf(prefs.hiddenRecent)
        private set

    /** Время последнего открытия: наше или из базы ONYX, что новее. 0 — книга убрана из «Недавних». */
    fun lastOpened(path: String, recent: Map<String, Long>): Long {
        val t = maxOf(recent[path] ?: 0L, reading[path]?.lastAccess ?: 0L)
        return if (t <= (hiddenRecent[path] ?: 0L)) 0L else t
    }

    fun setCover(book: Book, image: File) {
        viewModelScope.launch {
            if (library.setCustomCover(book, image)) {
                overlay = library.books.value.firstOrNull { it.path == book.path }?.let { Overlay.Details(it) }
            } else {
                Toast.makeText(getApplication(), "Не получилось прочитать картинку", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resetCover(book: Book) {
        viewModelScope.launch {
            library.resetCover(book)
            overlay = library.books.value.firstOrNull { it.path == book.path }?.let { Overlay.Details(it) }
        }
    }

    /** Убрать книгу из «Недавних» (с устройства она не удаляется). */
    fun hideFromRecent(book: Book) {
        hiddenRecent = hiddenRecent + (book.path to System.currentTimeMillis())
        prefs.hiddenRecent = hiddenRecent
    }

    /** Кнопка «Домой»: сбросить всё к главной странице. */
    fun goHome() {
        overlay = null
        closeSearch()
        group = null
        tab = Tab.ALL
        section = Section.LIBRARY
        pages.clear()
    }

    /** Обработка «Назад». Возвращает false, если закрывать уже нечего. */
    fun back(): Boolean = when {
        overlay is Overlay.Choice && (overlay as Overlay.Choice).under != null -> { overlay = (overlay as Overlay.Choice).under; true }
        overlay is Overlay.CoverPicker -> { overlay = Overlay.Details((overlay as Overlay.CoverPicker).book); true }
        overlay != null -> { overlay = null; true }
        section == Section.STORAGE && storageDir != storageRoot -> {
            storageDir = storageDir.parentFile ?: storageRoot; true
        }
        section != Section.LIBRARY -> { section = Section.LIBRARY; true }
        searchOpen -> { closeSearch(); true }
        group != null -> { group = null; true }
        tab != Tab.ALL -> { tab = Tab.ALL; setPage(mainKey, 0); true }
        page(mainKey) != 0 -> { setPage(mainKey, 0); true }
        else -> false
    }
}

/** Язык книги для фильтра: «ru», «en»… или «—», если не указан. */
fun langOf(book: Book): String = book.lang?.lowercase()?.take(2)?.takeIf { it.isNotBlank() } ?: "—"

fun langLabel(code: String): String = when (code) {
    "ru" -> "Русский"; "en" -> "Английский"; "uk" -> "Украинский"; "be" -> "Белорусский"
    "de" -> "Немецкий"; "fr" -> "Французский"; "es" -> "Испанский"; "it" -> "Итальянский"
    "ja" -> "Японский"; "zh" -> "Китайский"; "pl" -> "Польский"; "—" -> "Не указан"
    else -> code.uppercase()
}
