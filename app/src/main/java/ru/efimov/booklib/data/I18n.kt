package ru.efimov.booklib.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Язык интерфейса. Состояние наблюдаемое: при переключении Compose перерисует всё,
 * что вызывало [L], — без перезапуска приложения.
 */
object Lang {
    var english by mutableStateOf(false)

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale("ru")
}

/** Строка на текущем языке. */
fun L(ru: String, en: String): String = if (Lang.english) en else ru
