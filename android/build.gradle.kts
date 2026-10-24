plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器作为独立插件（版本跟随 Kotlin）
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
