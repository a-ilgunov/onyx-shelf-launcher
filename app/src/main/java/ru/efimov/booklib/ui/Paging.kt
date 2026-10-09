package ru.efimov.booklib.ui

import ru.efimov.booklib.data.L
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Перелистывание свайпом: справа налево или снизу вверх — следующая страница,
 * обратно — предыдущая. Страница меняется мгновенно, без анимации: на e-ink так быстрее всего.
 */
@Composable
fun Modifier.swipePages(onNext: () -> Unit, onPrev: () -> Unit): Modifier {
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrev)
    return pointerInput(Unit) {
        val threshold = 48.dp.toPx()
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragCancel = { total = Offset.Zero },
            onDragEnd = {
                val main = if (abs(total.x) >= abs(total.y)) total.x else total.y
                if (main < -threshold) next() else if (main > threshold) prev()
            },
        ) { change, drag ->
            change.consume()
            total += drag
        }
    }
}

class Geometry(val cols: Int, val rows: Int, val cellW: Dp, val cellH: Dp) {
    val perPage: Int get() = cols * rows
}

/**
 * Раскладка страницы. Для плиток с обложками ([flexible]) перебирает число колонок
 * и берёт то, при котором ряды заполняют высоту лучше всего, — чтобы внизу не оставалось пустоты.
 */
fun geometry(
    width: Dp,
    height: Dp,
    minCellW: Dp,
    cellH: (Dp) -> Dp,
    flexible: Boolean = false,
    extraCols: Int = 2,
    rows: Int? = null,
    cols: Int? = null,
): Geometry {
    // Сетка задана жёстко (например, 3×2 для крупных обложек)
    if (rows != null && cols != null) return Geometry(cols, rows, width / cols, height / rows)
    if (rows != null) {
        // Ровно [rows] рядов на всю высоту: берём самые крупные плитки, которые ещё помещаются
        val h = height / rows
        var cols = 1
        while (cellH(width / cols) > h && cols < 12) cols++
        return Geometry(cols, rows, width / cols, h)
    }
    fun make(cols: Int): Geometry {
        val cellW = width / cols
        val h = cellH(cellW)
        return Geometry(cols, (height / h).toInt().coerceAtLeast(1), cellW, h)
    }
    val base = (width / minCellW).toInt().coerceAtLeast(1)
    if (!flexible) return make(base)
    return (base..base + extraCols).map(::make)
        .filter { it.cellW >= minCellW * 0.7f }
        .maxBy { g -> (g.rows * g.cellH.value / height.value * 100).toInt() / 4 * 1000 - g.cols }
}

/** Неподвижная сетка ровно из тех элементов, что поместились на страницу. */
@Composable
fun <T> StaticGrid(items: List<T>, g: Geometry, cell: @Composable (T) -> Unit) {
    Column {
        for (r in 0 until g.rows) {
            if (r * g.cols >= items.size) break
            Row {
                for (c in 0 until g.cols) {
                    val i = r * g.cols + c
                    Box(Modifier.size(g.cellW, g.cellH)) { if (i < items.size) cell(items[i]) }
                }
            }
        }
    }
}

/**
 * Список или сетка, разбитые на страницы под размер экрана.
 * [leading] — необязательная первая страница (главная с «Сейчас читаю»).
 */
@Composable
fun <T> PagedGrid(
    items: List<T>,
    page: Int,
    onPage: (Int) -> Unit,
    minCellWidth: Dp,
    cellHeight: (Dp) -> Dp,
    modifier: Modifier = Modifier,
    flexible: Boolean = false,
    rows: Int? = null,
    cols: Int? = null,
    empty: String = L("Пусто", "Empty"),
    leading: (@Composable () -> Unit)? = null,
    prefetch: (suspend (List<T>) -> Unit)? = null,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val indicatorH = 32.dp
        val pad = 6.dp
        val g = geometry(maxWidth - pad * 2, maxHeight - indicatorH - pad * 2, minCellWidth, cellHeight, flexible, rows = rows, cols = cols)
        val lead = if (leading != null) 1 else 0
        val listPages = if (items.isEmpty()) 1 else (items.size + g.perPage - 1) / g.perPage
        val count = listPages + lead
        val p = page.coerceIn(0, count - 1)

        fun slice(listPage: Int): List<T> {
            val start = listPage * g.perPage
            return if (start >= items.size || start < 0) emptyList() else items.subList(start, minOf(items.size, start + g.perPage))
        }

        if (prefetch != null) {
            // Обложки следующей страницы готовим заранее, чтобы они не «всплывали»
            LaunchedEffect(p, g.perPage, items) { prefetch(slice(p - lead + 1)) }
        }

        // Главная занимает весь экран: без отступов и номера страницы
        val onLeading = lead == 1 && p == 0
        Column(
            Modifier.fillMaxSize().swipePages(
                onNext = { if (p < count - 1) onPage(p + 1) },
                onPrev = { if (p > 0) onPage(p - 1) },
            )
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(if (onLeading) 0.dp else pad)) {
                when {
                    lead == 1 && p == 0 -> leading!!()
                    items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(empty, fontSize = 18.sp, color = Gray)
                    }
                    else -> StaticGrid(slice(p - lead), g, cell)
                }
            }
            if (!onLeading) PageIndicator(p, count, indicatorH) { onPage(it) }
        }
    }
}

@Composable
private fun PageIndicator(page: Int, count: Int, height: Dp, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        if (count <= 1) return@Row
        // Нажатие по краям — тоже листание, для тех, кто не любит свайпы
        Box(Modifier.weight(1f).height(height).clickable(enabled = page > 0) { onPage(page - 1) }, contentAlignment = Alignment.Center) {
            if (page > 0) Text("‹", fontSize = 22.sp, color = Gray)
        }
        Text("${page + 1} / $count", fontSize = 14.sp, color = Gray)
        Box(Modifier.weight(1f).height(height).clickable(enabled = page < count - 1) { onPage(page + 1) }, contentAlignment = Alignment.Center) {
            if (page < count - 1) Text("›", fontSize = 22.sp, color = Gray)
        }
    }
}
