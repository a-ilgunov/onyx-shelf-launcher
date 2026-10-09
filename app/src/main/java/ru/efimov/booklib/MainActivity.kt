package ru.efimov.booklib

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.efimov.booklib.ui.EinkTheme
import ru.efimov.booklib.ui.HomeScreen
import ru.efimov.booklib.ui.LibraryViewModel
import ru.efimov.booklib.ui.Paper
import ru.efimov.booklib.ui.Palette
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Книги передаются читалкам как file:// — нужно для сохранения позиции чтения в читалке ONYX
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        // Тема — до первого кадра, чтобы не мигнуть светлым экраном
        Palette.dark = ru.efimov.booklib.data.Prefs(this).darkTheme

        setContent {
            EinkTheme {
                // Фон окна тоже под тему (виден на мгновение при переходах)
                androidx.compose.runtime.SideEffect {
                    window.decorView.setBackgroundColor(Paper.toArgb())
                    // Статус-бар — под тему: цвет фона и светлые/тёмные значки
                    window.statusBarColor = Paper.toArgb()
                    window.navigationBarColor = Paper.toArgb()
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !Palette.dark
                        isAppearanceLightNavigationBars = !Palette.dark
                        // Boox рисует статус-бар сам и не красит его под приложение,
                        // поэтому в тёмной теме прячем его (вместо него — своя строка в цветах темы)
                        if (Palette.dark) {
                            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                        } else {
                            show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                        }
                    }
                }
                BackHandler(enabled = hasAccess) {
                    // Главный экран не закрывается по «Назад», как и обычный лаунчер
                    if (!vm.back() && !isDefaultHome()) finish()
                }
                if (hasAccess) HomeScreen(vm) else AccessScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = Environment.isExternalStorageManager()
        if (hasAccess) vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Нажали «Домой», уже находясь здесь — вернуться к началу библиотеки
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome()
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == packageName
    }

    @androidx.compose.runtime.Composable
    private fun AccessScreen() {
        Column(
            Modifier.fillMaxSize().background(Paper).padding(40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Нужен доступ к файлам", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "Чтобы найти книги в памяти устройства, разрешите приложению доступ ко всем файлам.",
                fontSize = 18.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }) { Text("Открыть настройки", fontSize = 18.sp) }
        }
    }
}
