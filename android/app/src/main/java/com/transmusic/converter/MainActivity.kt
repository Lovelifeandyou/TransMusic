package com.transmusic.converter

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transmusic.converter.core.ConvertViewModel
import com.transmusic.converter.core.Prefs
import com.transmusic.converter.ui.ConvertScreen
import com.transmusic.converter.ui.theme.ThemeMode
import com.transmusic.converter.ui.theme.TransMusicTheme

/**
 * 单一 Activity + Jetpack Compose。
 * 主题模式（跟随系统 / 浅色 / 深色）存在 Prefs 里，切换后 Composable 立即重组，
 * 系统栏样式也随之调整（深色主题用浅色图标，浅色主题用深色图标）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = Prefs(this)

        setContent {
            var themeModeValue by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
            val systemDark = isSystemInDarkTheme()
            val themeMode = ThemeMode.fromInt(themeModeValue)
            val dark = when (themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> systemDark
            }

            // 系统栏跟随主题：深色背景配浅色图标，浅色背景配深色图标
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }

            TransMusicTheme(darkTheme = dark) {
                val viewModel: ConvertViewModel = viewModel()
                ConvertScreen(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    onThemeModeChange = { next ->
                        prefs.themeMode = next.ordinal
                        themeModeValue = next.ordinal
                    },
                )
            }
        }
    }
}
