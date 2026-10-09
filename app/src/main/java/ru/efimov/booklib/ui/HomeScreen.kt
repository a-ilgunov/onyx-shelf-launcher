package ru.efimov.booklib.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.efimov.booklib.BuildConfig
import ru.efimov.booklib.data.Book
import ru.efimov.booklib.data.Formats
import java.io.File
import java.text.Collator
import java.util.Locale

private val Panel = RoundedCornerShape(28.dp)
private val Pill = RoundedCornerShape(26.dp)

@Composable
fun HomeScreen(vm: LibraryViewModel) {
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize()) {
            // В тёмной теме системный статус-бар Boox скрыт (он всегда белый) — вместо него своя строка
            if (Palette.dark) StatusLine()
            Box(Modifier.weight(1f)) {
                when (vm.section) {
                    Section.LIBRARY -> LibraryScreen(vm)
                    Section.APPS -> AppsScreen(vm)
                    Section.STORAGE -> StorageScreen(vm)
                    Section.SETTINGS -> SettingsScreen(vm)
                }
            }
            BottomBar(vm.section) { s ->
                // Повторное нажатие на «Библиотеку» возвращает на главную
                if (s == Section.LIBRARY && vm.section == Section.LIBRARY) vm.goHome() else vm.openSection(s)
            }
        }
        when (val o = vm.overlay) {
            is Overlay.Details -> DetailsOverlay(vm, o.book)
            is Overlay.CoverPicker -> CoverPicker(vm, o.book)
            is Overlay.ViewOptions -> ViewOptionsOverlay(vm)
            is Overlay.NewShelf -> NewShelfOverlay(vm, o.book)
            is Overlay.Duplicates -> DuplicatesScreen(vm)
            is Overlay.HomeEditor -> HomeEditorScreen(vm)
            is Overlay.Choice -> {
                if (o.under is Overlay.HomeEditor) HomeEditorScreen(vm)
                ChoiceOverlay(o) { vm.overlay = o.under }
            }
            null -> {}
        }
    }
}

private val sectionIcons: Map<Section, ImageVector> = mapOf(
    Section.LIBRARY to AppIcons.Library,
    Section.APPS to AppIcons.Apps,
    Section.STORAGE to AppIcons.Folder,
    Section.SETTINGS to AppIcons.Settings,
)

/** Нижняя панель как в iOS: серые контурные иконки, выбранная — чёрная с точкой под ней. */
@Composable
private fun BottomBar(selected: Section, onSelect: (Section) -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Section.entries.forEach { s ->
            val sel = s == selected
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { onSelect(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(sectionIcons.getValue(s), s.label, tint = if (sel) Ink else Muted, modifier = Modifier.size(27.dp))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(5.dp).background(if (sel) Ink else Paper, CircleShape))
            }
        }
    }
}

/** Крупный заголовок экрана в духе iOS: «Приложения», «Память», «Настройки». */
@Composable
private fun LargeTitle(text: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
        if (onBack != null) {
            Row(
                Modifier.clickable(onClick = onBack).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Back, null, tint = Ink, modifier = Modifier.size(20.dp))
                Text("Назад", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
            }
        } else {
            Text(subtitle ?: " ", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Gray)
        }
        Text(
            text, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay,
            letterSpacing = (-0.8).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onBack != null && subtitle != null) Text(subtitle, fontSize = 15.sp, color = Gray)
    }
}

// ---------- Слои поверх экрана ----------

/** Подложка: нажатие мимо панели закрывает слой. Без затемнения — на e-ink оно рисуется грязным растром. */
@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.9f)
                .clip(Panel)
                .background(Paper)
                .border(1.5.dp, Muted, Panel)
                // Нажатия внутри панели не должны её закрывать
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
    }
}

@Composable
private fun ChoiceOverlay(o: Overlay.Choice, close: () -> Unit) {
    Scrim(close) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                o.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            o.options.forEachIndexed { i, label ->
                Row(
                    Modifier.fillMaxWidth().clickable { close(); o.onSelect(i) }.padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, fontSize = 18.sp, fontWeight = if (i == o.selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (i == o.selected) Text("✓", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).background(if (filled) Ink else Fill, Pill).clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Paper else Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).background(Fill, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Ink, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).background(Fill, RoundedCornerShape(19.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsOverlay(vm: LibraryViewModel, book: Book) {
    val close = { vm.overlay = null }
    val progress = vm.reading[book.path]
    val ctx = LocalContext.current
    // Сколько читали именно эту книгу — по статистике ONYX
    val readMs by androidx.compose.runtime.produceState<Long?>(null, progress?.hash) {
        value = progress?.hash?.let { h -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ru.efimov.booklib.data.OnyxReading.readingTime(ctx, h) } }
    }
    Scrim(close) {
        Column(Modifier.padding(24.dp)) {
            Row {
                Cover(book, Modifier.width(150.dp), showFormat = false, radius = 12.dp)
                Column(Modifier.padding(start = 20.dp).weight(1f)) {
                    Text(
                        book.title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay,
                        letterSpacing = (-0.5).sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Text(book.authorsDisplay, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                    book.seriesLabel?.let { Text(it, fontSize = 15.sp, color = Gray, modifier = Modifier.padding(top = 2.dp)) }
                    if (progress != null && progress.total > 0) {
                        Text(
                            if (progress.finished) "Прочитано" else "${progress.percent}% · стр. ${progress.current} из ${progress.total}",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    readMs?.takeIf { it >= 60_000 }?.let { ms ->
                        val h = ms / 3_600_000
                        val m = ms / 60_000 % 60
                        val t = if (h > 0) "$h ч $m мин" else "$m мин"
                        Text(
                            if (progress?.finished == true) "Прочитана за $t" else "Читаете $t",
                            fontSize = 15.sp, color = OnFill, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (book.genres.isNotEmpty()) {
                        Text(book.genreNames().joinToString(", "), fontSize = 14.sp, color = Gray, modifier = Modifier.padding(top = 10.dp))
                    }
                    Text(
                        "${book.format.uppercase()} · ${"%.1f".format(book.size / 1048576.0)} МБ",
                        fontSize = 14.sp, color = Gray, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // Аннотация обрезается, а не прокручивается
            book.annotation?.let {
                Text(
                    it, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 9, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            if (book.authors.isNotEmpty() || book.seriesList.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    book.authors.forEach { a -> Chip("Все книги: ${a.display}") { close(); vm.openGroup(Group(Tab.AUTHORS, a.key, a.sortName)) } }
                    book.seriesList.forEach { sr -> Chip("Серия «${sr.name}»") { close(); vm.openGroup(Group(Tab.SERIES, sr.key, sr.name)) } }
                }
            }
            Row(
                Modifier.padding(top = 22.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton("Читать", filled = true, modifier = Modifier.weight(0.8f)) { close(); vm.openBook(book) }
                PillButton("Открыть в…", filled = false, modifier = Modifier.weight(1f)) { close(); vm.openBook(book, choose = true) }
                RoundIconButton(AppIcons.Shelf, "На полку") { vm.chooseShelf(book) }
                RoundIconButton(AppIcons.Image, "Сменить обложку") { vm.overlay = Overlay.CoverPicker(book) }
                // Только из списка «Недавние» — сам файл остаётся на месте
                if (vm.lastOpened(book.path, vm.prefs.recent.value) > 0) {
                    RoundIconButton(AppIcons.HideRecent, "Убрать из недавних") { close(); vm.hideFromRecent(book) }
                }
            }
        }
    }
}

// ---------- Приложения ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsScreen(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Приложения", vm.apps.size.takeIf { it > 0 }?.let { "$it установлено" })
        PagedGrid(
            items = vm.apps,
            page = vm.page("apps"),
            onPage = { vm.setPage("apps", it) },
            minCellWidth = 100.dp,
            cellHeight = { 112.dp },
        ) { app ->
            Column(
                Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = {
                            launch(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(ComponentName(app.pkg, app.activity))
                                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                        },
                        // Долгое нажатие — «О приложении» (удалить, очистить, разрешения)
                        onLongClick = {
                            launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                        },
                    )
                    .padding(top = 10.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Многие иконки — чёрные линии на прозрачном фоне: в тёмной теме подкладываем светлую плашку
                Image(
                    app.icon, contentDescription = null,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (Palette.dark) Color(0xFFF2F2F2) else Color.Transparent),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    app.label, fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun launch(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Не удалось открыть", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Нет доступа", Toast.LENGTH_SHORT).show()
    }
}

// ---------- Память ----------

private val fileCollator: Collator = Collator.getInstance(Locale("ru"))

@Composable
private fun StorageScreen(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val dir = vm.storageDir
    val books by vm.library.books.collectAsState()
    val entries = remember(dir) {
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { !it.name.startsWith('.') }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(fileCollator) { it.name })
    }
    val atRoot = dir == vm.storageRoot

    Column(Modifier.fillMaxSize()) {
        if (atRoot) {
            val free = vm.storageRoot.usableSpace / 1_073_741_824.0
            val total = vm.storageRoot.totalSpace / 1_073_741_824.0
            LargeTitle("Память", "Свободно %.1f из %.0f ГБ".format(free, total))
        } else {
            LargeTitle(dir.name) { vm.storageDir = dir.parentFile ?: vm.storageRoot }
        }
        PagedGrid(
            items = entries,
            page = vm.page("dir:${dir.path}"),
            onPage = { vm.setPage("dir:${dir.path}", it) },
            minCellWidth = 10_000.dp,
            cellHeight = { 66.dp },
            empty = "Папка пуста",
        ) { f ->
            val info = if (f.isDirectory) "" else {
                val size = f.length()
                if (size >= 1_048_576) "%.1f МБ".format(size / 1_048_576.0) else "${size / 1024} КБ"
            }
            FileRow(f.name, info, f.isDirectory) {
                if (f.isDirectory) {
                    vm.storageDir = f
                } else {
                    val book = books.firstOrNull { it.path == f.path }
                    if (book != null) vm.openBook(book) else openFile(ctx, f)
                }
            }
        }
    }
}

@Composable
private fun FileRow(name: String, info: String, isDir: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxSize().clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(Fill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(if (isDir) AppIcons.Folder else AppIcons.File, null, tint = Ink, modifier = Modifier.size(24.dp))
        }
        Text(
            name, fontSize = 17.sp, fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        Text(info, fontSize = 14.sp, color = Gray, modifier = Modifier.padding(start = 12.dp))
        if (isDir) Text("›", fontSize = 22.sp, color = Gray, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun openFile(ctx: Context, f: File) {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val mime = Formats.formatOf(f.name)?.let { Formats.mime(it) }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), mime)
    launch(ctx, Intent.createChooser(intent, f.name))
}

// ---------- Настройки ----------

private class SettingItem(val title: String, val subtitle: String? = null, val onLong: (() -> Unit)? = null, val action: () -> Unit = {})

/** Настройки — сгруппированными карточками, как в iOS. Помещаются на один экран, листать не нужно. */
@Composable
private fun SettingsScreen(vm: LibraryViewModel) {
    val ctx = LocalContext.current
    val scan by vm.library.scan.collectAsState()

    val groups = listOf(
        "Библиотека" to listOf(
            SettingItem("Чем открывать книги", Readers.label(ctx, vm.readerPackage)) {
                val readers = Readers.list(ctx)
                val options = listOf<String?>(null) + readers.map { it.pkg }
                vm.overlay = Overlay.Choice(
                    "Чем открывать книги",
                    listOf("Запоминать последний выбор") + readers.map { it.label },
                    options.indexOf(vm.readerPackage).coerceAtLeast(0),
                ) { vm.setReader(options[it]) }
            },
            SettingItem("Обновить библиотеку", scan?.let { "Идёт поиск: ${it.done} из ${it.total}" } ?: "Найти новые книги в памяти") { vm.rescan() },
            SettingItem(
                "Уточнить серии через интернет",
                vm.seriesFix?.let { "Сверяю с Фантлабом: ${it.first} из ${it.second}" }
                    ?: vm.library.seriesFixedCount.takeIf { it > 0 }?.let { "Фантлаб · уточнено ${booksWord(it)} · долгое нажатие — сбросить" }
                    ?: "Сверить циклы и номера книг с Фантлабом",
                onLong = if (vm.library.seriesFixedCount > 0) {
                    { vm.overlay = Overlay.Choice("Уточнения серий", listOf("Сбросить все уточнения"), -1) { vm.clearSeriesFixes() } }
                } else null,
            ) { vm.fixSeriesOnline() },
            SettingItem(
                "Найти дубликаты",
                vm.hiddenBooks.size.takeIf { it > 0 }?.let { "Скрыто ${booksWord(it)} · долгое нажатие — вернуть" } ?: "Одинаковые книги в разных файлах",
                onLong = if (vm.hiddenBooks.isNotEmpty()) {
                    { vm.overlay = Overlay.Choice("Скрытые книги", listOf("Вернуть все скрытые книги"), -1) { vm.unhideAll() } }
                } else null,
            ) { vm.overlay = Overlay.Duplicates },
        ),
        "Устройство" to listOf(
            SettingItem("Wi-Fi") { launch(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) },
            SettingItem("Bluetooth") { launch(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            SettingItem("Экран и яркость") { launch(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            SettingItem("Все настройки") { launch(ctx, Intent(Settings.ACTION_SETTINGS)) },
        ),
        "Главный экран" to listOf(
            SettingItem("Настроить главный экран", "Плитки и полка внизу") { vm.overlay = Overlay.HomeEditor },
            SettingItem("Тёмная тема", if (Palette.dark) "Включена · обложки не инвертируются" else "Выключена") { vm.toggleDark() },
            SettingItem("Открыть лаунчер ONYX", "Настройки Boox, магазин, облако") {
                launch(ctx, Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.onyx", "com.onyx.StartupActivity")))
            },
            SettingItem("Выбрать главный экран", "Чтобы вернуть ONYX, выберите его здесь") {
                launch(ctx, Intent(Settings.ACTION_HOME_SETTINGS))
            },
        ),
    )

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Настройки", "Моя библиотека ${BuildConfig.VERSION_NAME}")
        // Все настройки помещаются на один экран — без прокрутки
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { (title, items) ->
                Column {
                    Text(
                        title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnFill, letterSpacing = 0.7.sp,
                        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )
                    if (title == "Устройство") {
                        // Короткие пункты — кнопками по две в ряд, чтобы настройки помещались на экран
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { item ->
                                        Box(
                                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(18.dp)).background(Fill)
                                                .clickable(onClick = item.action).padding(horizontal = 18.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) { Text(item.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    } else Column(Modifier.fillMaxWidth().clip(Panel).background(Fill)) {
                        items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 20.dp))
                            SettingsRow(item)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(item: SettingItem, minHeight: Dp = 50.dp) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = item.action, onLongClick = item.onLong).heightIn(min = minHeight).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 17.sp)
            item.subtitle?.let { Text(it, fontSize = 14.sp, color = OnFill, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("›", fontSize = 22.sp, color = OnFill)
    }
}

// ---------- Выбор своей обложки ----------

private object ThumbCache {
    val lru = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 2
    }

    /** Уменьшенная копия картинки: фотографии бывают по 10 МБ, целиком их декодировать незачем. */
    fun load(f: File): androidx.compose.ui.graphics.ImageBitmap? {
        lru.get(f.path)?.let { return it }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 240) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path, opts) ?: return null
        return bmp.asImageBitmap().also { lru.put(f.path, it) }
    }
}

@Composable
private fun CoverPicker(vm: LibraryViewModel, book: Book) {
    val images by androidx.compose.runtime.produceState<List<File>?>(null, book.path) { value = vm.library.findImages(book) }
    // С включённым Wi-Fi — ещё и обложки изданий с Фантлаба и Open Library
    val online = remember { vm.isOnline() }
    val web by androidx.compose.runtime.produceState<List<ru.efimov.booklib.data.OnlineCover>?>(if (online) null else emptyList(), book.path) {
        if (online) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ru.efimov.booklib.data.OnlineCovers.find(book) }
    }
    val back = { vm.overlay = Overlay.Details(book) }
    Column(
        Modifier.fillMaxSize().background(Paper)
            // Слой закрывает экран целиком, нажатия под него не проходят
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val status = when {
            !online -> "Включите Wi-Fi, чтобы искать обложки изданий в интернете"
            web == null -> "Ищу обложки изданий в интернете…"
            web!!.isEmpty() -> "В интернете обложек не нашлось"
            else -> "Из интернета: ${web!!.size} · дальше — картинки с устройства"
        }
        LargeTitle("Обложка", book.title, onBack = back)
        Text(status, fontSize = 14.sp, color = OnFill, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
        Box(Modifier.weight(1f)) {
            if (images == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ищу картинки в памяти…", fontSize = 17.sp, color = Gray) }
            } else {
                val items: List<Any> = web.orEmpty() + images!!
                PagedGrid(
                    items = items,
                    page = vm.page("covers:${book.path}"),
                    onPage = { vm.setPage("covers:${book.path}", it) },
                    minCellWidth = 130.dp,
                    cellHeight = { w -> (w - 16.dp) * 1.5f + 34.dp },
                    rows = 3,
                    empty = "Картинок не найдено. Положите изображение в папку с книгой или в «Обложки».",
                ) { item ->
                    when (item) {
                        is ru.efimov.booklib.data.OnlineCover -> OnlineThumb(item) { vm.setCoverFromUrl(book, item.url) }
                        is File -> ImageThumb(item) { vm.setCover(book, item) }
                    }
                }
            }
        }
        if (vm.library.hasCustomCover(book.path)) {
            PillButton(
                "Вернуть исходную обложку", filled = false,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
            ) { vm.resetCover(book) }
        }
    }
}

/** Обложка издания из интернета: скачивается в кэш и показывается уменьшенной. */
@Composable
private fun OnlineThumb(cover: ru.efimov.booklib.data.OnlineCover, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, cover.url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ru.efimov.booklib.data.OnlineCovers.download(ctx, cover.url)?.let { ThumbCache.load(it) }
        }
    }
    Column(Modifier.fillMaxSize().clickable(onClick = onPick).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Fill)
                .border(1.dp, Hairline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("…", fontSize = 18.sp, color = Gray)
        }
        Text(cover.label.ifEmpty { "Издание" }, fontSize = 11.sp, color = OnFill, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ImageThumb(f: File, onPick: () -> Unit) {
    val bmp = remember(f.path) { ThumbCache.load(f) }
    Column(Modifier.fillMaxSize().clickable(onClick = onPick).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Fill)
                .border(1.dp, Hairline, RoundedCornerShape(10.dp)),
        ) {
            if (bmp != null) {
                Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(f.name, fontSize = 11.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- Вид, сортировка и фильтры ----------

@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).background(if (selected) Ink else Fill, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, color = if (selected) Paper else Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnFill, letterSpacing = 0.7.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ViewOptionsOverlay(vm: LibraryViewModel) {
    val books by vm.library.books.collectAsState()
    // Только те форматы и языки, что реально есть в библиотеке
    val formats = remember(books) { books.groupingBy { it.format }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val langs = remember(books) { books.groupingBy { langOf(it) }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val close = { vm.overlay = null }
    Scrim(close) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text("Вид и фильтры", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay)

            OptionSection("Вид") {
                ru.efimov.booklib.data.ViewMode.entries.forEach { v -> OptionChip(v.label, v == vm.viewMode) { vm.setView(v) } }
                OptionChip("Серии стопкой", vm.seriesStacks) { vm.toggleStacks() }
            }
            OptionSection("Сортировка") {
                ru.efimov.booklib.data.SortMode.entries.forEach { m -> OptionChip(m.label, m == vm.sort) { vm.setSortMode(m) } }
                OptionChip(if (vm.sortDesc) "↑ Обратный порядок" else "↓ Обычный порядок", vm.sortDesc) { vm.toggleSortDesc() }
            }
            OptionSection("Статус") {
                ru.efimov.booklib.data.ReadStatus.entries.forEach { st -> OptionChip(st.label, st in vm.filterStatus) { vm.toggleStatus(st) } }
            }
            if (formats.size > 1) {
                OptionSection("Формат") { formats.forEach { f -> OptionChip(f.uppercase(), f in vm.filterFormats) { vm.toggleFormat(f) } } }
            }
            if (langs.size > 1) {
                OptionSection("Язык") { langs.forEach { l -> OptionChip(langLabel(l), l in vm.filterLangs) { vm.toggleLang(l) } } }
            }

            Row(Modifier.padding(top = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Сбросить фильтры", filled = false, modifier = Modifier.weight(1f)) { vm.resetFilters() }
                PillButton("Готово", filled = true, modifier = Modifier.weight(1f), onClick = close)
            }
        }
    }
}

// ---------- Новая полка ----------

@Composable
private fun NewShelfOverlay(vm: LibraryViewModel, book: Book?) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    val close = { vm.overlay = null }
    Scrim(close) {
        Column(Modifier.padding(24.dp)) {
            Text("Новая полка", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay)
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(54.dp).background(Fill, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) Text("Например, «На отпуск»", fontSize = 18.sp, color = Gray)
                // Курсор прозрачный — мигание заставляло бы e-ink перерисовываться
                androidx.compose.foundation.text.BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Ink, fontFamily = Inter),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                    modifier = Modifier.fillMaxWidth().then(androidx.compose.ui.Modifier.focusRequesterCompat(focus)),
                )
            }
            Row(Modifier.padding(top = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Отмена", filled = false, modifier = Modifier.weight(1f), onClick = close)
                PillButton("Создать", filled = true, modifier = Modifier.weight(1f)) { vm.createShelf(name, book); close() }
            }
        }
    }
}

private fun Modifier.focusRequesterCompat(f: androidx.compose.ui.focus.FocusRequester) =
    this.then(androidx.compose.ui.Modifier.focusRequester(f))

// ---------- Дубликаты ----------

/** Одинаковое название и автор в разных файлах. Лишнюю копию можно скрыть — файл не удаляется. */
@Composable
private fun DuplicatesScreen(vm: LibraryViewModel) {
    val books by vm.library.books.collectAsState()
    val copies = remember(books, vm.hiddenBooks) {
        books.filter { it.path !in vm.hiddenBooks }
            .groupBy { ru.efimov.booklib.data.Fantlab.norm(it.title) + "|" + (it.authors.firstOrNull()?.key ?: "") }
            .values.filter { it.size > 1 }
            .sortedWith(compareBy(fileCollator) { it.first().title })
            .flatten()
    }
    Column(
        Modifier.fillMaxSize().background(Paper)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LargeTitle("Дубликаты", if (copies.isEmpty()) "Повторов не найдено" else "Найдено ${booksWord(copies.size)} с повторами") { vm.overlay = null }
        PagedGrid(
            items = copies,
            page = vm.page("dups"),
            onPage = { vm.setPage("dups", it) },
            minCellWidth = 10_000.dp,
            cellHeight = { 108.dp },
            empty = "Одинаковых книг нет",
        ) { b ->
            val p = vm.reading[b.path]
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(b, Modifier.width(58.dp), showFormat = false, radius = 6.dp)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.path.removePrefix(vm.storageRoot.path + "/"), fontSize = 12.sp, color = Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${b.format.uppercase()} · ${"%.1f".format(b.size / 1048576.0)} МБ" +
                                (p?.takeIf { it.total > 0 }?.let { if (it.finished) " · прочитано" else " · ${it.percent}%" } ?: ""),
                            fontSize = 12.sp, color = OnFill,
                        )
                    }
                    Box(
                        Modifier.padding(start = 10.dp).height(40.dp).background(Fill, RoundedCornerShape(20.dp))
                            .clickable { vm.hideBook(b) }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Скрыть", fontSize = 15.sp) }
                }
                HorizontalDivider(color = LightGray)
            }
        }
    }
}

// ---------- Настройка главного экрана ----------

@Composable
private fun HomeEditorScreen(vm: LibraryViewModel) {
    val widgets = ru.efimov.booklib.data.HomeWidget.entries
    val shelves = ru.efimov.booklib.data.HomeShelf.entries
    val places = listOf("Верхний ряд, слева", "Верхний ряд, справа", "Нижний ряд, слева", "Нижний ряд, справа")
    val groups = listOf(
        "Плитки" to places.mapIndexed { i, place ->
            SettingItem(place, vm.homeWidgets[i].label) {
                vm.overlay = Overlay.Choice(place, widgets.map { it.label }, widgets.indexOf(vm.homeWidgets[i]), under = Overlay.HomeEditor) {
                    vm.setHomeWidget(i, widgets[it])
                }
            }
        },
        "Полка внизу" to listOf(
            SettingItem("Что показывать", vm.homeShelf.label) {
                vm.overlay = Overlay.Choice("Полка внизу", shelves.map { it.label }, shelves.indexOf(vm.homeShelf), under = Overlay.HomeEditor) {
                    vm.chooseHomeShelf(shelves[it])
                }
            },
        ),
    )
    Column(
        Modifier.fillMaxSize().background(Paper)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LargeTitle("Главный экран", "Пустой ряд плиток скрывается, «Сейчас читаю» становится крупнее") { vm.overlay = null }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            groups.forEach { (title, items) ->
                Column {
                    Text(
                        title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnFill, letterSpacing = 0.7.sp,
                        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )
                    Column(Modifier.fillMaxWidth().clip(Panel).background(Fill)) {
                        items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 20.dp))
                            SettingsRow(item)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Своя строка состояния (для тёмной темы) ----------

/**
 * Время и заряд в цветах темы. Обновляется только по системным событиям — смена минуты
 * и изменение заряда; своих таймеров нет, так что лишних перерисовок e-ink не добавляет.
 */
@Composable
private fun StatusLine() {
    val ctx = LocalContext.current
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", Locale("ru")) }
    var time by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(fmt.format(java.util.Date())) }
    var battery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var charging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                        if (level >= 0) battery = level * 100 / scale
                        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                        charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
                    }
                    else -> time = fmt.format(java.util.Date())
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ctx.registerReceiver(receiver, filter)
        time = fmt.format(java.util.Date())
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
        battery?.let { b ->
            Text((if (charging) "⚡ " else "") + "$b %", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
        }
    }
}
