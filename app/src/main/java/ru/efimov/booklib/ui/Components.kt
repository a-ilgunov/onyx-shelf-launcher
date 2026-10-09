package ru.efimov.booklib.ui

import ru.efimov.booklib.data.L
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ru.efimov.booklib.R
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.efimov.booklib.data.Book
import ru.efimov.booklib.data.Progress

/**
 * Палитра со светлой и тёмной темой. Цвета — геттеры, которые читают [Palette.dark]:
 * при переключении темы Compose сам перерисует всё, что их использует. Обложки — картинки, их это не касается.
 */
object Palette {
    var dark by mutableStateOf(false)
}

private fun pick(light: Long, dark: Long) = Color(if (Palette.dark) dark else light)

val Ink: Color get() = pick(0xFF111111, 0xFFF2F2F2)
val Paper: Color get() = pick(0xFFFFFFFF, 0xFF000000)
val Gray: Color get() = pick(0xFF6B6B6B, 0xFFA6A6A6)
val LightGray: Color get() = pick(0xFFEDEDED, 0xFF2A2A2A)

/** Приглушённые иконки, рамки слоёв, разделитель в плитке. */
val Muted: Color get() = pick(0xFF8A8A8A, 0xFF8A8A8A)

/** Разделители строк в карточках настроек. */
val Divider: Color get() = pick(0xFFBDBDBD, 0xFF4A4A4A)

/** Дорожка полосы прогресса. */
val Track: Color get() = pick(0xFFE0E0E0, 0xFF3C3C3C)

/** Книга без обложки: фон и текст карточки. */
val Placeholder: Color get() = pick(0xFFDCDCDC, 0xFF303030)
val PlaceholderText: Color get() = pick(0xFF333333, 0xFFDDDDDD)

/** «Листы» под обложкой в стопке серии. */
val Sheet1: Color get() = pick(0xFFCFCFCF, 0xFF404040)
val Sheet2: Color get() = pick(0xFFE6E6E6, 0xFF2C2C2C)

/** Фон плиток-виджетов и круглых кнопок. На e-ink светлее #DDD почти не виден. */
val Fill: Color get() = pick(0xFFE3E3E3, 0xFF2E2E2E)

/** Вторичный текст на плитках: на сером фоне нужен темнее обычного серого. */
val OnFill: Color get() = pick(0xFF3A3A3A, 0xFFD0D0D0)

/** Тонкая обводка обложек. */
val Hairline: Color get() = pick(0xFFD0D0D0, 0xFF4A4A4A)

/** Inter — свободный шрифт в духе San Francisco (SF нельзя использовать вне устройств Apple). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Начертание для крупных заголовков, как SF Pro Display. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private fun interTypography(): Typography {
    val t = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

/** Чёрно-белая тема без «волн» от нажатий — на e-ink они только мусорят экран. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    val typography = remember { interTypography() }
    MaterialTheme(
        typography = typography,
        colorScheme = (if (Palette.dark) androidx.compose.material3.darkColorScheme(
            primary = Ink, onPrimary = Paper,
            secondary = Ink, onSecondary = Paper,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink,
            surfaceVariant = LightGray, onSurfaceVariant = Ink,
            surfaceContainerHigh = Paper, surfaceContainer = Paper,
            outline = Ink,
        ) else lightColorScheme(
            primary = Ink, onPrimary = Paper,
            secondary = Ink, onSecondary = Paper,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink,
            surfaceVariant = LightGray, onSurfaceVariant = Ink,
            surfaceContainerHigh = Paper, surfaceContainer = Paper,
            outline = Ink,
        )),
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            // Без межстрочных отступов и разрядки Material — текст плотнее, как в iOS
            LocalTextStyle provides TextStyle(fontFamily = Inter, color = Ink),
            content = content,
        )
    }
}

/**
 * Обложки текущей страницы декодируются сразу (это быстро), чтобы на e-ink
 * не было второй перерисовки, когда картинка «догружается».
 */
object CoverCache {
    private val lru = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }

    fun key(book: Book): String? = book.cover?.let { "$it@${book.modified}" }

    fun get(book: Book): ImageBitmap? {
        val k = key(book) ?: return null
        lru.get(k)?.let { return it }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(book.cover, opts)?.asImageBitmap() ?: return null
        lru.put(k, bmp)
        return bmp
    }

    suspend fun prefetch(books: List<Book>) = withContext(Dispatchers.IO) { books.forEach { get(it) } }
}

@Composable
fun Cover(
    book: Book,
    modifier: Modifier = Modifier,
    progress: Progress? = null,
    showFormat: Boolean = true,
    radius: Dp = 10.dp,
    // null — обложка заполняет отведённый ей прямоугольник (лишнее обрезается)
    ratio: Float? = 2f / 3f,
) {
    val bmp = remember(CoverCache.key(book)) { CoverCache.get(book) }
    val shape = RoundedCornerShape(radius)
    BoxWithConstraints((if (ratio != null) modifier.aspectRatio(ratio) else modifier).clip(shape).border(1.dp, Hairline, shape)) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Книга без обложки: серая карточка с названием внизу
            Column(
                Modifier.fillMaxSize().background(Placeholder).padding(9.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    book.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, color = PlaceholderText,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    book.authorsDisplay, fontSize = 10.sp, color = Gray, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (showFormat) {
            Text(
                book.format.uppercase(),
                color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Ink, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (progress != null && progress.total > 0) {
            // Метка всегда в одну строку: на узкой обложке от «Прочитано» остаётся только галочка
            Text(
                if (progress.finished) (if (maxWidth >= 118.dp) L("✓ Прочитано", "✓ Finished") else "✓") else "${progress.percent}%",
                color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Ink, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}


val BOOK_ROW_HEIGHT = 104.dp
val GROUP_ROW_HEIGHT = 68.dp
/**
 * Общая рамка плитки с обложкой: обложка всегда 2:3 (стандарт для книг) и по центру ячейки,
 * подпись — ровно по ширине обложки. Свободное место уходит в поля, а не в обрезку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverTileFrame(
    padding: Dp,
    captionHeight: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    cover: @Composable (Modifier) -> Unit,
    caption: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(padding)) {
        val coverH = minOf(maxHeight - captionHeight, maxWidth * 1.5f).coerceAtLeast(0.dp)
        val coverW = coverH / 1.5f
        Column(Modifier.width(coverW).align(Alignment.TopCenter)) {
            cover(Modifier.size(coverW, coverH))
            caption()
        }
    }
}

/** Подпись под обложкой: название в одну строку и серая строка под ним, вплотную. */
@Composable
fun TileCaption(title: String, subtitle: String, titleSize: Int = 14) {
    Text(
        title, fontSize = titleSize.sp, lineHeight = (titleSize + 3).sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp),
    )
    if (subtitle.isNotEmpty()) {
        Text(subtitle, fontSize = (titleSize - 2).sp, lineHeight = (titleSize + 1).sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Высота подписи (название + серая строка) для расчёта сетки. */
fun captionHeight(titleSize: Int): Dp = (7 + titleSize + 3 + titleSize + 1 + 2).dp

/** Высота плитки книги в сетке при данной ширине ячейки. */
fun bookTileHeight(cellW: Dp): Dp = (cellW - 16.dp) * 1.5f + captionHeight(14) + 16.dp

@Composable
fun BookTile(book: Book, progress: Progress?, subtitle: String, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    CoverTileFrame(8.dp, captionHeight(14), { onOpen(book) }, { onDetails(book) },
        cover = { m -> Cover(book, m, progress, showFormat = false, ratio = null) },
        caption = { TileCaption(book.title, subtitle) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookRow(book: Book, progress: Progress?, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onDetails(book) })
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(book, Modifier.width(60.dp), showFormat = false, radius = 6.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(book.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book.authorsDisplay, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.seriesLabel?.let { Text(it, fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (progress != null && progress.total > 0) {
                Text(
                    if (progress.finished) "✓" else "${progress.percent}%",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        HorizontalDivider(color = LightGray)
    }
}

data class GroupItem(val key: String, val title: String, val subtitle: String?, val count: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(g: GroupItem, onClick: (GroupItem) -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth().combinedClickable(onClick = { onClick(g) }, onLongClick = onLongClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                g.subtitle?.let { Text(it, fontSize = 14.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (g.count > 0) Text(g.count.toString(), fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
        }
        HorizontalDivider(color = LightGray)
    }
}

/** Тонкая полоска прогресса с процентом; ничего не рисует, если книгу не открывали. */
@Composable
fun ProgressLine(p: Progress?, modifier: Modifier = Modifier) {
    if (p == null || p.total == 0) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(6.dp).background(Track, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(p.fraction).height(6.dp).background(Ink, RoundedCornerShape(3.dp)))
        }
        Text(p.label, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

// ---------- Дополнительные виды библиотеки ----------

fun smallTileHeight(cellW: Dp): Dp = (cellW - 12.dp) * 1.5f + captionHeight(12) + 12.dp
fun coverOnlyHeight(cellW: Dp): Dp = (cellW - 10.dp) * 1.5f + 10.dp
val DETAILED_ROW_HEIGHT = 150.dp
val COMPACT_ROW_HEIGHT = 58.dp
/** Мелкие обложки. */
@Composable
fun BookTileSmall(book: Book, progress: Progress?, subtitle: String, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    CoverTileFrame(6.dp, captionHeight(12), { onOpen(book) }, { onDetails(book) },
        cover = { m -> Cover(book, m, progress, showFormat = false, radius = 8.dp, ratio = null) },
        caption = { TileCaption(book.title, subtitle, titleSize = 12) },
    )
}
/** Только обложки, без подписей — максимум книг на экране. */
@Composable
fun CoverOnlyTile(book: Book, progress: Progress?, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    CoverTileFrame(5.dp, 0.dp, { onOpen(book) }, { onDetails(book) },
        cover = { m -> Cover(book, m, progress, showFormat = false, radius = 8.dp, ratio = null) },
    )
}

/** Подробный список: обложка, серия, жанры, начало аннотации, прогресс, формат. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookDetailedRow(book: Book, progress: Progress?, genres: String?, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onDetails(book) })
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Cover(book, Modifier.fillMaxHeight(), progress, showFormat = false, radius = 8.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f).fillMaxHeight()) {
                Text(book.title, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.authorsDisplay, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val meta = listOfNotNull(book.seriesLabel, genres).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.annotation?.let {
                    Text(
                        it.replace('\n', ' '), fontSize = 13.sp, lineHeight = 17.sp, color = OnFill, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            progress == null || progress.total == 0 -> L("Не начата", "Not started")
                            progress.finished -> L("✓ Прочитано", "✓ Finished")
                            else -> L("${progress.percent}% · стр. ${progress.current} из ${progress.total}", "${progress.percent}% · page ${progress.current} of ${progress.total}")
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${book.format.uppercase()} · ${"%.1f".format(book.size / 1048576.0)} ${L("МБ", "MB")}",
                        fontSize = 12.sp, color = Gray,
                    )
                }
            }
        }
        HorizontalDivider(color = LightGray)
    }
}

/** Компактный список: только текст, много книг на экране. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCompactRow(book: Book, progress: Progress?, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = { onOpen(book) }, onLongClick = { onDetails(book) })
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                book.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.4f),
            )
            Text(
                book.authorsDisplay, fontSize = 14.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            Text(
                when {
                    progress == null || progress.total == 0 -> ""
                    progress.finished -> "✓"
                    else -> "${progress.percent}%"
                },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).width(44.dp),
                textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = LightGray)
    }
}

/** Крупные обложки: шесть книг на экран. */
fun hugeTileHeight(cellW: Dp): Dp = (cellW - 20.dp) * 1.5f + captionHeight(17) + 20.dp
@Composable
fun BookTileHuge(book: Book, progress: Progress?, subtitle: String, onOpen: (Book) -> Unit, onDetails: (Book) -> Unit) {
    CoverTileFrame(10.dp, captionHeight(17), { onOpen(book) }, { onDetails(book) },
        cover = { m -> Cover(book, m, progress, showFormat = false, radius = 12.dp, ratio = null) },
        caption = { TileCaption(book.title, subtitle, titleSize = 17) },
    )
}
/** Серия стопкой: обложка первой книги поверх двух «листов». */
@Composable
fun StackTile(first: Book, name: String, subtitle: String, showText: Boolean, onClick: () -> Unit) {
    CoverTileFrame(8.dp, if (showText) captionHeight(14) else 0.dp, onClick,
        cover = { m ->
            Box(m) {
                val sheet = RoundedCornerShape(10.dp)
                Box(Modifier.fillMaxSize().padding(start = 10.dp, bottom = 10.dp).background(Sheet1, sheet).border(1.dp, Hairline, sheet))
                Box(Modifier.fillMaxSize().padding(5.dp).background(Sheet2, sheet).border(1.dp, Hairline, sheet))
                Cover(first, Modifier.fillMaxSize().padding(top = 10.dp, end = 10.dp), showFormat = false, ratio = null)
            }
        },
        caption = { if (showText) TileCaption(name, subtitle) },
    )
}

@Composable
fun StackRow(first: Book, name: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(first, Modifier.width(48.dp), showFormat = false, radius = 6.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(L("Серия «$name»", "Series “$name”"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 13.sp, color = Gray, maxLines = 1)
            }
            Text("›", fontSize = 22.sp, color = Gray)
        }
        HorizontalDivider(color = LightGray)
    }
}
/** Книга серии, которой нет на устройстве: пунктирная «пустая обложка». */
@Composable
fun MissingTile(work: ru.efimov.booklib.data.CycleWork, showText: Boolean) {
    CoverTileFrame(8.dp, if (showText) captionHeight(14) else 0.dp, onClick = {},
        cover = { m ->
            Box(m.dashedBorder(Muted, 10.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("№ ${work.index}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Gray)
                    // Мелкий шрифт: на узкой плитке длинные слова иначе рвутся по буквам
                    Text(
                        work.title, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        maxLines = 5, overflow = TextOverflow.Ellipsis, color = OnFill, modifier = Modifier.padding(top = 6.dp),
                    )
                    work.year?.let { Text(it.toString(), fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        caption = { if (showText) TileCaption(work.title, L("Нет на книге", "Not on device")) },
    )
}

@Composable
fun MissingRow(work: ru.efimov.booklib.data.CycleWork) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("№ ${work.index}", fontSize = 14.sp, color = Gray, modifier = Modifier.width(48.dp))
            Text(work.title, fontSize = 16.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(L("нет на устройстве", "not on device"), fontSize = 13.sp, color = Gray)
        }
        HorizontalDivider(color = LightGray)
    }
}

/** Пунктирная скруглённая рамка. */
fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = this.then(
    Modifier.drawBehind {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()), style = stroke)
    }
)
