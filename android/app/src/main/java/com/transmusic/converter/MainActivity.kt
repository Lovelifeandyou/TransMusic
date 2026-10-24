package com.transmusic.converter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transmusic.converter.core.ConvertViewModel
import com.transmusic.converter.ui.ConvertScreen
import com.transmusic.converter.ui.theme.TransMusicTheme

/**
 * 单一 Activity + Jetpack Compose。
 * UI 全部由 MD3E（Material Design 3 Expressive）组件与自定义 Expressive 风格构成，
 * 转码逻辑在 core/engine 层，与 Windows 桌面版共用同一套参数。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TransMusicTheme {
                val viewModel: ConvertViewModel = viewModel()
                ConvertScreen(viewModel)
            }
        }
    }
}
