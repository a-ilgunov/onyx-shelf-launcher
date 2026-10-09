package ru.efimov.booklib.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.efimov.booklib.data.Author
import ru.efimov.booklib.data.Book
import ru.efimov.booklib.data.Genres
import ru.efimov.booklib.data.HomeShelf
import ru.efimov.booklib.data.HomeWidget
import ru.efimov.booklib.data.Progress
import ru.efimov.booklib.data.ScanState
import ru.efimov.booklib.data.CycleWork
import ru.efimov.booklib.data.Fantlab
import ru.efimov.booklib.data.ReadStatus
import ru.efimov.booklib.data.SortMode
import ru.efimov.booklib.data.ViewMode
import android.content.Intent
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

fun booksWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "книга"
        m10 in 2..4 && m100 !in 12..14 -> "книги"
        else -> "книг"
    }
    return "$n $w"
}

private fun Book.matches(q: String): Boolean =
    q.isBlank() || title.contains(q, true) || authorsDisplay.contains(q, true) ||
        authors.any { it.sortName.contains(q, true) } || series?.contains(q, true) == true

private val bySeries: Comparator<Book> =
    compareBy<Book> { it.series == null }
        .thenBy(collator) { it.series ?: "" }
        .thenBy { it.seriesIndex ?: Float.MAX_VALUE }
        .thenBy(collator) { it.title }

/** Порядок книг внутри серии [key]: по номеру в ней, затем по подциклу и номеру в нём. */
private fun seriesOrder(key: String): Comparator<Book> =
    compareBy<Book> { it.indexIn(key) == null }
        .thenBy { it.indexIn(key) ?: Float.MAX_VALUE }
        .thenBy(collator) { it.primarySeries?.name ?: "" }
        .thenBy { it.seriesIndex ?: Float.MAX_VALUE }
        .thenBy(collator) { it.title }

/** Несколько книг одной серии — одной стопкой. */
data class SeriesStack(val key: String, val name: String, val books: List<Book>)

private fun stackify(list: List<Book>): List<Any> {
    val bySeries = list.filter { it.primarySeries != null }.groupBy { it.primarySeries!!.key }
    val seen = HashSet<String>()
    val out = ArrayList<Any>()
    for (b in list) {
        val k = b.primarySeries?.key
        val group = k?.let { bySeries[it] }
        if (k == null || group == null || group.size < 2) out.add(b)
        else if (seen.add(k)) out.add(SeriesStack(k, b.primarySeries!!.name, group.sortedWith(seriesOrder(k))))
    }
    return out
}

/** Полка: автоматическая («Продолжить серию»…) или своя. */
data class Shelf(val key: String, val title: String, val subtitle: String, val books: List<Book>, val custom: Boolean)

private fun shelvesFor(vm: LibraryViewModel, books: List<Book>, recent: Map<String, Long>): List<Shelf> {
    val now = System.currentTimeMillis()
    val month = 30L * 24 * 3600 * 1000
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val yearStart = java.util.Calendar.getInstance().apply { set(year, 0, 1, 0, 0, 0) }.timeInMillis

    // Следующая книга серии после последней дочитанной
    val cont = books.filter { it.seriesIndex != null }.groupBy { it.primarySeries!!.key }.values.mapNotNull { group ->
        val sorted = group.sortedBy { it.seriesIndex }
        val lastDone = sorted.indexOfLast { vm.statusOf(it) == ReadStatus.FINISHED }
        if (lastDone < 0) null else sorted.drop(lastDone + 1).firstOrNull { vm.statusOf(it) != ReadStatus.FINISHED }
    }.sortedByDescending { vm.lastOpened(it.path, recent) }

    val reading = books.filter { vm.statusOf(it) == ReadStatus.READING }.sortedByDescending { vm.lastOpened(it.path, recent) }
    val fresh = books.filter { now - it.modified < month }.sortedByDescending { it.modified }
    val abandoned = books.filter {
        val opened = vm.lastOpened(it.path, recent)
        vm.statusOf(it) == ReadStatus.READING && opened in 1 until now - month && (vm.reading[it.path]?.percent ?: 0) < 30
    }
    val thisYear = books.filter { vm.statusOf(it) == ReadStatus.FINISHED && (vm.reading[it.path]?.lastAccess ?: 0) >= yearStart }

    val byPath = books.associateBy { it.path }
    val auto = listOf(
        Shelf("auto:continue", "Продолжить серию", "следующие книги в начатых сериях", cont, false),
        Shelf("auto:reading", "Читаю сейчас", "начатые и не дочитанные", reading, false),
        Shelf("auto:new", "Новые поступления", "добавлены за последний месяц", fresh, false),
        Shelf("auto:abandoned", "Брошенные", "открывали больше месяца назад, прочитано меньше 30%", abandoned, false),
        Shelf("auto:year", "Прочитано в $year", "дочитанные в этом году", thisYear, false),
    )
    val custom = vm.shelves.map { (name, paths) -> Shelf("shelf:$name", name, "своя полка", paths.mapNotNull { byPath[it] }, true) }
    return auto + custom
}

/** Сортировка по выбранному полю; «обратный порядок» переворачивает естественное направление. */
private fun List<Book>.sortedFor(vm: LibraryViewModel, recent: Map<String, Long>): List<Book> {
    val base = when (vm.sort) {
        SortMode.TITLE -> sortedWith(compareBy(collator) { it.title })
        SortMode.AUTHOR -> sortedWith(compareBy<Book, String>(collator) { it.firstAuthorSort }.then(bySeries))
        SortMode.SERIES -> sortedWith(bySeries)
        SortMode.ADDED -> sortedByDescending { it.modified }
        SortMode.OPENED -> sortedByDescending { vm.lastOpened(it.path, recent) }
        SortMode.PROGRESS -> sortedByDescending { vm.reading[it.path]?.takeIf { p -> p.total > 0 }?.fraction ?: -1f }
        SortMode.SIZE -> sortedByDescending { it.size }
    }
    return if (vm.sortDesc) base.reversed() else base
}

private fun Book.authorKeys(): List<String> =
    if (authors.isEmpty()) listOf(Author.NO_AUTHOR.lowercase()) else authors.map { it.key }

fun Book.genreNames(): List<String> = genres.map { Genres.name(it) }.distinct().ifEmpty { listOf("Без жанра") }

@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val all by vm.library.books.collectAsState()
    val recent by vm.prefs.recent.collectAsState()

    val open: (Book) -> Unit = { vm.openBook(it) }
    val details: (Book) -> Unit = { vm.overlay = Overlay.Details(it) }
    val q = vm.query.trim()
    val group = vm.group
    // Фильтры (статус, формат, язык) действуют во всех разделах; поиск — поверх них
    // Скрытые копии не показываются нигде
    val visible = remember(all, vm.hiddenBooks) { all.filter { it.path !in vm.hiddenBooks } }
    val filtered = remember(visible, vm.filterStatus, vm.filterFormats, vm.filterLangs, vm.reading) { visible.filter { vm.passesFilters(it) } }
    val books = remember(filtered, q) { filtered.filter { it.matches(q) } }

    // Главная страница («Сейчас читаю») стоит первой, пока не открыта группа и нет поиска
    val home: (@Composable () -> Unit)? = if (group == null && q.isBlank()) {
        {
            HomePage(
                vm, visible, recent, open, details,
                onSearch = { vm.searchOpen = true },
                onStats = { launch(ctx, Intent("onyx.settings.action.STATISTICS")) },
                toLibrary = { vm.setPage(vm.mainKey, 1) },
                toRecent = { vm.selectTab(Tab.RECENT) },
            )
        }
    } else null
    // На главной своя шапка («Сегодня»), общая панель там не нужна
    val onHome = home != null && !vm.searchOpen && vm.page(vm.mainKey) == 0

    Column(Modifier.fillMaxSize()) {
        if (!onHome) TopBar(vm, visible.size, filtered.size)

        when {
            group != null -> {
                val list = remember(books, group, vm.sort) {
                    when (group.tab) {
                        Tab.AUTHORS -> books.filter { group.key in it.authorKeys() }.sortedWith(bySeries)
                        Tab.SERIES -> books.filter { b -> b.seriesList.any { it.key == group.key } }.sortedWith(seriesOrder(group.key))
                        Tab.SHELVES -> shelvesFor(vm, books, recent).firstOrNull { it.key == group.key }?.books.orEmpty()
                        Tab.GENRES -> books.filter { group.key in it.genreNames() }.sortedFor(vm, recent)
                        else -> books
                    }
                }
                // В серии после своих книг — те, которых на устройстве нет (по составу цикла с Фантлаба)
                val cycles by vm.library.cycles.collectAsState()
                // Только в обычном просмотре серии: с фильтром или поиском показываем лишь то, что есть.
                // Сверяем со всеми книгами серии на устройстве, а не с отфильтрованными.
                val plainView = !vm.filtersActive && q.isBlank()
                val missing = remember(visible, cycles, group, plainView) {
                    if (group.tab != Tab.SERIES || !plainView) emptyList()
                    else {
                        val have = visible.filter { b -> b.seriesList.any { it.key == group.key } }.map { Fantlab.norm(it.title) }
                        cycles[group.key].orEmpty().filter { w ->
                            val t = Fantlab.norm(w.title)
                            t.isNotEmpty() && have.none { it == t || t in it }
                        }
                    }
                }
                val sub: (Book) -> String = when (group.tab) {
                    Tab.AUTHORS -> { b -> b.seriesLabel ?: "" }
                    Tab.SERIES -> { b ->
                        val n = b.indexIn(group.key)?.let { "№ ${Book.formatIndex(it)}" }
                        listOfNotNull(n, b.authorsDisplay).joinToString(" · ")
                    }
                    else -> { b -> b.authorsDisplay }
                }
                BookPages(vm, list + missing, "group:${group.tab}:${group.key}", open, details, subtitle = sub)
            }

            vm.tab == Tab.ALL -> {
                val list = remember(books, vm.sort, vm.sortDesc, vm.reading, recent, vm.seriesStacks) {
                    books.sortedFor(vm, recent).let { if (vm.seriesStacks) stackify(it) else it }
                }
                BookPages(vm, list, if (q.isBlank()) vm.mainKey else "search:$q", open, details, home)
            }

            vm.tab == Tab.RECENT -> {
                val list = books.filter { vm.lastOpened(it.path, recent) > 0 }
                    .sortedByDescending { vm.lastOpened(it.path, recent) }
                BookPages(vm, list, vm.mainKey, open, details, home, "Здесь появятся книги, которые вы открывали")
            }

            vm.tab == Tab.SHELVES -> {
                val shelves = shelvesFor(vm, books, recent)
                val items = shelves.map { GroupItem(it.key, it.title, it.subtitle, it.books.size) } +
                    GroupItem("new", "+ Новая полка", "своя подборка книг", 0)
                PagedGrid(
                    items = items,
                    page = vm.page(vm.mainKey),
                    onPage = { vm.setPage(vm.mainKey, it) },
                    minCellWidth = 10_000.dp,
                    cellHeight = { GROUP_ROW_HEIGHT },
                    leading = home,
                ) { g ->
                    GroupRow(
                        g,
                        onClick = { if (it.key == "new") vm.overlay = Overlay.NewShelf(null) else vm.openGroup(Group(Tab.SHELVES, it.key, it.title)) },
                        // Свою полку можно удалить долгим нажатием
                        onLongClick = if (g.key.startsWith("shelf:")) {
                            {
                                val name = g.key.removePrefix("shelf:")
                                vm.overlay = Overlay.Choice("Полка «$name»", listOf("Удалить полку"), -1) { vm.deleteShelf(name) }
                            }
                        } else null,
                    )
                }
            }

            else -> {
                val groups = remember(filtered, vm.tab, q) { groupsFor(vm.tab, filtered, q) }
                val key = if (q.isBlank()) vm.mainKey else "search:${vm.tab}:$q"
                PagedGrid(
                    items = groups,
                    page = vm.page(key),
                    onPage = { vm.setPage(key, it) },
                    minCellWidth = 10_000.dp,
                    cellHeight = { GROUP_ROW_HEIGHT },
                    empty = if (vm.tab == Tab.SERIES) "Серий пока нет" else "Ничего не найдено",
                    leading = home,
                ) { g -> GroupRow(g, onClick = { vm.openGroup(Group(vm.tab, it.key, it.title)) }) }
            }
        }
    }
}

private fun groupsFor(tab: Tab, all: List<Book>, q: String): List<GroupItem> = when (tab) {
    Tab.AUTHORS -> all.flatMap { b -> b.authors.ifEmpty { listOf(Author("", "")) }.map { it to b } }
        .groupBy { it.first.key }
        .map { (key, pairs) -> GroupItem(key, pairs.first().first.sortName, booksWord(pairs.size), pairs.size) }
        .filter { q.isBlank() || it.title.contains(q, true) }
        .sortedWith(compareBy(collator) { it.title })

    Tab.SERIES -> all.flatMap { b -> b.seriesList.map { it to b } }
        .groupBy({ it.first.key }, { it })
        .map { (key, pairs) ->
            val list = pairs.map { it.second }
            GroupItem(
                key, pairs.first().first.name,
                list.flatMap { it.authors }.distinctBy { it.key }.joinToString(", ") { it.display }.ifEmpty { null },
                list.size,
            )
        }
        .filter { q.isBlank() || it.title.contains(q, true) || it.subtitle?.contains(q, true) == true }
        .sortedWith(compareBy(collator) { it.title })

    Tab.GENRES -> all.flatMap { b -> b.genreNames().map { it to b } }
        .groupBy({ it.first }, { it.second })
        .map { (name, list) -> GroupItem(name, name, null, list.size) }
        .filter { q.isBlank() || it.title.contains(q, true) }
        .sortedWith(compareBy(collator) { it.title })

    else -> emptyList()
}

@Composable
private fun BookPages(
    vm: LibraryViewModel,
    books: List<Any>,
    key: String,
    open: (Book) -> Unit,
    details: (Book) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    empty: String = "Книги не найдены",
    // Серая строка под названием: по умолчанию автор, внутри автора — серия, внутри серии — номер
    subtitle: (Book) -> String = { it.authorsDisplay },
) {
    val mode = vm.viewMode
    PagedGrid(
        items = books,
        page = vm.page(key),
        onPage = { vm.setPage(key, it) },
        minCellWidth = when (mode) {
            ViewMode.COVERS_HUGE -> 180.dp
            ViewMode.COVERS_LARGE -> 140.dp
            ViewMode.COVERS_SMALL -> 104.dp
            ViewMode.COVERS_ONLY -> 96.dp
            else -> 10_000.dp
        },
        cellHeight = when (mode) {
            ViewMode.COVERS_HUGE -> ::hugeTileHeight
            ViewMode.COVERS_LARGE -> ::bookTileHeight
            ViewMode.COVERS_SMALL -> ::smallTileHeight
            ViewMode.COVERS_ONLY -> ::coverOnlyHeight
            ViewMode.LIST -> { _ -> BOOK_ROW_HEIGHT }
            ViewMode.DETAILED -> { _ -> DETAILED_ROW_HEIGHT }
            ViewMode.COMPACT -> { _ -> COMPACT_ROW_HEIGHT }
        },
        // Обложки — ровными рядами на всю высоту, без пустой полосы внизу
        rows = when (mode) {
            ViewMode.COVERS_HUGE -> 2
            ViewMode.COVERS_LARGE -> 3
            ViewMode.COVERS_SMALL, ViewMode.COVERS_ONLY -> 4
            else -> null
        },
        // Сетки заданы жёстко: крупные 3×2, обычные 5×3, мелкие 6×4
        cols = when (mode) {
            ViewMode.COVERS_HUGE -> 3
            ViewMode.COVERS_LARGE -> 5
            ViewMode.COVERS_SMALL -> 6
            else -> null
        },
        empty = if (vm.filtersActive) "Под фильтры ничего не подходит" else empty,
        leading = leading,
        prefetch = if (mode == ViewMode.COMPACT) null else { list ->
            CoverCache.prefetch(list.mapNotNull { if (it is SeriesStack) it.books.first() else it as? Book })
        },
    ) { item ->
        if (item is CycleWork) {
            if (mode.covers) MissingTile(item, showText = mode != ViewMode.COVERS_ONLY) else MissingRow(item)
            return@PagedGrid
        }
        if (item is SeriesStack) {
            val done = item.books.count { vm.statusOf(it) == ReadStatus.FINISHED }
            val sub = booksWord(item.books.size) + if (done > 0) " · прочитано $done" else ""
            val onClick = { vm.openGroup(Group(Tab.SERIES, item.key, item.name), keepTab = true) }
            if (mode.covers) StackTile(item.books.first(), item.name, sub, showText = mode != ViewMode.COVERS_ONLY, onClick = onClick)
            else StackRow(item.books.first(), item.name, sub, onClick)
            return@PagedGrid
        }
        val b = item as Book
        val p = vm.reading[b.path]
        when (mode) {
            ViewMode.COVERS_HUGE -> BookTileHuge(b, p, subtitle(b), open, details)
            ViewMode.COVERS_LARGE -> BookTile(b, p, subtitle(b), open, details)
            ViewMode.COVERS_SMALL -> BookTileSmall(b, p, subtitle(b), open, details)
            ViewMode.COVERS_ONLY -> CoverOnlyTile(b, p, open, details)
            ViewMode.LIST -> BookRow(b, p, open, details)
            ViewMode.DETAILED -> BookDetailedRow(b, p, b.genres.takeIf { it.isNotEmpty() }?.let { b.genreNames().take(2).joinToString(", ") }, open, details)
            ViewMode.COMPACT -> BookCompactRow(b, p, open, details)
        }
    }
}

// ---------- Верхняя панель ----------

@Composable
private fun TopBar(vm: LibraryViewModel, total: Int, shown: Int) {
    // Прогресс поиска книг перерисовывает только эту строку, а не весь экран
    val scan by vm.library.scan.collectAsState()
    val group = vm.group
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            vm.searchOpen -> SearchField(vm)

            group != null -> {
                BarIcon(AppIcons.Back, "Назад") { vm.group = null }
                Text(
                    group.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                OptionsIcon(vm)
            }

            else -> {
                // Заголовок — он же переключатель разделов: Все книги / Авторы / Серии / Жанры / Недавние
                Row(
                    Modifier
                        .clickable {
                            vm.overlay = Overlay.Choice("Показывать", Tab.entries.map { it.label }, vm.tab.ordinal) {
                                vm.selectTab(Tab.entries[it])
                            }
                        }
                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.tab.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay, letterSpacing = (-0.4).sp)
                    Icon(AppIcons.Chevron, null, tint = Ink, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                }
                Text(
                    scan?.let { if (it.total == 0) "поиск книг…" else "обновление ${it.done}/${it.total}" }
                        ?: if (vm.filtersActive) "$shown из $total" else booksWord(total),
                    fontSize = 13.sp, color = Gray, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                BarIcon(AppIcons.Search, "Поиск") { vm.searchOpen = true }
                OptionsIcon(vm)
            }
        }
    }
    HorizontalDivider(color = Divider)
}

/** Кнопка «Вид, сортировка и фильтры»; точка — если какой-то фильтр включён. */
@Composable
private fun OptionsIcon(vm: LibraryViewModel) {
    Box {
        BarIcon(AppIcons.Filter, "Вид, сортировка и фильтры") { vm.overlay = Overlay.ViewOptions }
        if (vm.filtersActive) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(9.dp).background(Ink, CircleShape))
        }
    }
}

@Composable
fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, description, tint = Ink, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchField(vm: LibraryViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Icon(AppIcons.Search, null, tint = Ink, modifier = Modifier.padding(start = 10.dp, end = 8.dp).size(22.dp))
    Box(Modifier.weight(1f)) {
        if (vm.query.isEmpty()) Text("Название, автор или серия", fontSize = 18.sp, color = Gray)
        // Курсор прозрачный: мигание заставляло бы e-ink перерисовываться дважды в секунду
        BasicTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, color = Ink),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    BarIcon(AppIcons.Close, "Закрыть") { vm.closeSearch() }
}

// ---------- Главная страница ----------

private val WidgetShape = RoundedCornerShape(28.dp)

/** Подпись-капитель над блоками: «СЕЙЧАС ЧИТАЮ», «ПРОЧИТАНО»… */
@Composable
private fun Caps(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnFill, letterSpacing = 0.7.sp)
}

private fun wordForBooks(n: Int): String = booksWord(n).substringAfter(' ')

@Composable
private fun HomePage(
    vm: LibraryViewModel,
    all: List<Book>,
    recent: Map<String, Long>,
    open: (Book) -> Unit,
    details: (Book) -> Unit,
    onSearch: () -> Unit,
    onStats: () -> Unit,
    toLibrary: () -> Unit,
    toRecent: () -> Unit,
) {
    val byLast = remember(all, recent, vm.reading, vm.hiddenRecent) {
        all.filter { vm.lastOpened(it.path, recent) > 0 }.sortedByDescending { vm.lastOpened(it.path, recent) }
    }
    val current = byLast.firstOrNull()
    val others = byLast.drop(1)
    val (shelfLabel, recentShelf) = if (others.isNotEmpty()) "Недавние" to others
    else "Недавно добавленные" to all.sortedByDescending { it.modified }.filter { it != current }
    val paths = remember(all) { all.mapTo(HashSet()) { it.path } }
    // Число из статистики чтения ONYX; если она недоступна — по отметкам «прочитано» в библиотеке
    val finished = vm.finishedTotal ?: vm.reading.count { (path, p) -> p.finished && path in paths }
        val today = remember {
        SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date()).replaceFirstChar { it.uppercase() }
    }

    val newest = remember(all) { all.filter { it.cover != null && it.format != "cbz" }.sortedByDescending { it.modified }.take(3) }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        // Шапка: дата, статистика ONYX и поиск
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(today, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Gray, modifier = Modifier.weight(1f))
            RoundAction(AppIcons.Stats, "Статистика чтения", onStats)
            Spacer(Modifier.width(10.dp))
            RoundAction(AppIcons.Search, "Поиск", onSearch)
        }

        // Сейчас читаю — большая обложка слева, забирает всё свободное место по высоте
        if (current != null) {
            NowReading(
                current, vm.reading[current.path], vm.lastOpened(current.path, recent), open, { details(current) },
                Modifier.weight(1f).padding(top = 16.dp),
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Откройте любую книгу — она появится здесь", fontSize = 17.sp, color = Gray)
            }
        }

        // Плитки — по настройкам: до двух рядов по две; пустой ряд не показывается
        val shelves = remember(all, recent, vm.reading, vm.shelves) { shelvesFor(vm, all, recent) }
        fun shelf(key: String) = shelves.firstOrNull { it.key == key }
        vm.homeWidgets.chunked(2).forEach { pair ->
            val tiles = pair.filter { it != HomeWidget.NONE }
            if (tiles.isEmpty()) return@forEach
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                tiles.forEach { w ->
                    HomeWidgetTile(
                        w, Modifier.weight(1f), vm, all, finished, newest, ::shelf, open, current?.path,
                        onStats = onStats, toLibrary = toLibrary,
                    )
                }
            }
        }

        // Полка внизу — тоже по настройкам
        val (shelfTitle, shelfBooks, shelfKey) = when (vm.homeShelf) {
            HomeShelf.RECENT -> Triple(shelfLabel, recentShelf, null)
            HomeShelf.CONTINUE -> Triple("Продолжить серию", shelf("auto:continue")?.books.orEmpty(), "auto:continue")
            HomeShelf.WANT -> Triple("Хочу прочитать", shelf("shelf:Хочу прочитать")?.books.orEmpty(), "shelf:Хочу прочитать")
            HomeShelf.NEW -> Triple("Новые поступления", shelf("auto:new")?.books.orEmpty(), "auto:new")
            HomeShelf.NONE -> Triple("", emptyList(), null)
        }
        if (vm.homeShelf != HomeShelf.NONE) {
            Row(Modifier.padding(top = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(shelfTitle, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "Все ›", fontSize = 15.sp, color = OnFill,
                    modifier = Modifier.clickable {
                        if (shelfKey == null) toRecent() else vm.openGroup(Group(Tab.SHELVES, shelfKey, shelfTitle))
                    }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (shelfBooks.isEmpty()) {
                Text("Пока пусто", fontSize = 15.sp, color = Gray, modifier = Modifier.padding(top = 10.dp))
            } else {
                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (i in 0 until 5) {
                        val bk = shelfBooks.getOrNull(i)
                        Column(Modifier.weight(1f)) {
                            if (bk != null) ShelfCover(bk, vm.reading[bk.path], open, details)
                        }
                    }
                }
            }
        }
    }
}

/** «1 ч 58 мин» крупными цифрами. */
@Composable
private fun TimeValue(ms: Long?) {
    val total = (ms ?: 0L) / 60_000
    val h = total / 60
    val m = total % 60
    Row(verticalAlignment = Alignment.Bottom) {
        if (h > 0) {
            BigNumber(h.toString())
            Text("ч", fontSize = 15.sp, color = OnFill, modifier = Modifier.padding(start = 4.dp, end = 10.dp, bottom = 6.dp))
        }
        BigNumber(m.toString())
        Text("мин", fontSize = 15.sp, color = OnFill, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    }
}

/** Число и подпись рядом, справа — веер обложек. */
@Composable
private fun CountWithFan(count: Int, books: List<Book>) {
    Row(verticalAlignment = Alignment.Bottom) {
        BigNumber(count.toString())
        Text(
            wordForBooks(count), fontSize = 15.sp, color = OnFill,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).weight(1f),
        )
        val fan = books.filter { it.cover != null }.take(3)
        Box(Modifier.width((40 + 24 * (fan.size - 1).coerceAtLeast(0)).dp)) {
            fan.forEachIndexed { i, b ->
                Cover(b, Modifier.padding(start = (24 * i).dp).width(40.dp), showFormat = false, radius = 7.dp)
            }
        }
    }
}

@Composable
private fun HomeWidgetTile(
    w: HomeWidget,
    modifier: Modifier,
    vm: LibraryViewModel,
    all: List<Book>,
    finished: Int,
    newest: List<Book>,
    shelf: (String) -> Shelf?,
    open: (Book) -> Unit,
    currentPath: String?,
    onStats: () -> Unit,
    toLibrary: () -> Unit,
) {
    val openShelf = { key: String, title: String -> vm.openGroup(Group(Tab.SHELVES, key, title)) }
    val next = if (w == HomeWidget.CONTINUE) shelf("auto:continue")?.books?.firstOrNull { it.path != currentPath } else null
    val onClick: () -> Unit = when (w) {
        HomeWidget.FINISHED, HomeWidget.TODAY, HomeWidget.WEEK -> onStats
        HomeWidget.LIBRARY -> toLibrary
        HomeWidget.YEAR -> { { openShelf("auto:year", "Прочитано в этом году") } }
        HomeWidget.READING -> { { openShelf("auto:reading", "Читаю сейчас") } }
        HomeWidget.WANT -> { { openShelf("shelf:Хочу прочитать", "Хочу прочитать") } }
        HomeWidget.CONTINUE -> { { next?.let(open) ?: openShelf("auto:continue", "Продолжить серию") } }
        HomeWidget.NONE -> { {} }
    }
    Column(
        modifier.fillMaxHeight().background(Fill, WidgetShape).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        if (w == HomeWidget.CONTINUE && next != null) {
            // Обложка следующей книги и её название
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(next, Modifier.fillMaxHeight(), showFormat = false, radius = 7.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Caps("Продолжить серию")
                    Text(next.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    next.seriesLabel?.let { Text(it, fontSize = 13.sp, color = OnFill, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            return@Column
        }
        Caps(w.label)
        Spacer(Modifier.weight(1f))
        when (w) {
            HomeWidget.FINISHED -> Row(verticalAlignment = Alignment.CenterVertically) {
                // Слева — сколько книг дочитано, справа — общее время чтения (оба числа из статистики ONYX)
                BigNumber(finished.toString())
                vm.totalHours?.let { h ->
                    Box(Modifier.padding(horizontal = 16.dp).width(2.dp).height(42.dp).background(Muted, CircleShape))
                    BigNumber(String.format(Locale("ru"), "%.1f", h))
                    Text("ч", fontSize = 15.sp, color = OnFill, modifier = Modifier.align(Alignment.Bottom).padding(start = 6.dp, bottom = 6.dp))
                }
            }
            HomeWidget.LIBRARY -> CountWithFan(all.size, newest)
            HomeWidget.TODAY -> TimeValue(vm.todayMs)
            HomeWidget.WEEK -> TimeValue(vm.weekMs)
            HomeWidget.YEAR -> {
                val n = vm.yearFinished ?: shelf("auto:year")?.books?.size ?: 0
                Row(verticalAlignment = Alignment.Bottom) {
                    BigNumber(n.toString())
                    Text(wordForBooks(n), fontSize = 15.sp, color = OnFill, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                }
            }
            HomeWidget.READING -> shelf("auto:reading")?.books.orEmpty().let { CountWithFan(it.size, it) }
            HomeWidget.WANT -> shelf("shelf:Хочу прочитать")?.books.orEmpty().let { CountWithFan(it.size, it) }
            HomeWidget.CONTINUE -> Text("Других книг для продолжения пока нет", fontSize = 15.sp, color = OnFill)
            HomeWidget.NONE -> {}
        }
    }
}

@Composable
private fun BigNumber(text: String) {
    Text(
        text, fontSize = 50.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold,
        fontFamily = InterDisplay, letterSpacing = (-1.5).sp,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCover(b: Book, p: Progress?, open: (Book) -> Unit, details: (Book) -> Unit) {
    Column(Modifier.combinedClickable(onClick = { open(b) }, onLongClick = { details(b) })) {
        Cover(b, Modifier.fillMaxWidth(), showFormat = false, radius = 8.dp)
        Text(
            when {
                p == null || p.total == 0 -> "Не начата"
                p.finished -> "✓ Прочитано"
                else -> "${p.percent}%"
            },
            fontSize = 12.sp, color = OnFill, maxLines = 1, modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowReading(
    book: Book,
    progress: Progress?,
    lastOpened: Long,
    open: (Book) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier,
) {
    // Нажатие в любом месте блока открывает книгу, долгое — карточку с аннотацией.
    // Когда на главной много плиток, блок становится ниже — тогда он перестраивается в компактный вид.
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val compact = maxHeight < 300.dp
    Row(Modifier.fillMaxSize().combinedClickable(onClick = { open(book) }, onLongClick = onDetails)) {
        Cover(book, Modifier.fillMaxHeight(), showFormat = false, radius = 12.dp)
        Column(Modifier.padding(start = 26.dp, top = 6.dp, bottom = 6.dp).weight(1f).fillMaxHeight()) {
            Caps("Сейчас читаю")
            Text(
                book.title, fontSize = if (compact) 26.sp else 34.sp, lineHeight = if (compact) 30.sp else 38.sp,
                fontWeight = FontWeight.Bold, fontFamily = InterDisplay,
                letterSpacing = (-0.7).sp, maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (compact) 6.dp else 10.dp),
            )
            Text(
                book.authorsDisplay, fontSize = if (compact) 16.sp else 18.sp, maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (compact) 4.dp else 10.dp),
            )
            if (!compact) book.seriesLabel?.let { Text(it, fontSize = 15.sp, color = Gray, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.weight(1f))
            if (progress != null && progress.total > 0) {
                // Процент — над левым краем полоски, страницы — над правым, на одной линии
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (progress.finished) "Прочитано" else "${progress.percent}%",
                        fontSize = if (compact) 34.sp else 52.sp, lineHeight = if (compact) 34.sp else 52.sp,
                        fontWeight = FontWeight.Bold, fontFamily = InterDisplay,
                        letterSpacing = (-1.5).sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "страница ${progress.current} из ${progress.total}", fontSize = 15.sp, color = Gray,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                    )
                }
                Box(Modifier.padding(top = if (compact) 8.dp else 12.dp).fillMaxWidth().height(8.dp).background(Track, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(Ink, RoundedCornerShape(4.dp)))
                }
            }
            if (lastOpened > 0 && !compact) {
                Text(
                    "Открыта " + SimpleDateFormat("d MMMM в HH:mm", Locale("ru")).format(Date(lastOpened)),
                    fontSize = 15.sp, color = Gray, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).background(Fill, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Ink, modifier = Modifier.size(22.dp)) }
}
