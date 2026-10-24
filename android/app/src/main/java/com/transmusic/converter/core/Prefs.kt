package com.transmusic.converter.core

import android.content.Context

/** 记住上次的格式与参数，下次打开沿用（对应 Windows 版的 settings.json）。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("transmusic", Context.MODE_PRIVATE)

    var formatKey: String
        get() = sp.getString("format", "mp3") ?: "mp3"
        set(value) = sp.edit().putString("format", value).apply()

    var qualityIndex: Int
        get() = sp.getInt("quality", 0)
        set(value) = sp.edit().putInt("quality", value).apply()

    var compLevel: Int
        get() = sp.getInt("comp", -1)
        set(value) = sp.edit().putInt("comp", value).apply()

    var sampleRate: Int
        get() = sp.getInt("sr", 0)
        set(value) = sp.edit().putInt("sr", value).apply()

    var channels: Int
        get() = sp.getInt("ch", 0)
        set(value) = sp.edit().putInt("ch", value).apply()

    var keepMeta: Boolean
        get() = sp.getBoolean("meta", true)
        set(value) = sp.edit().putBoolean("meta", value).apply()

    var keepCover: Boolean
        get() = sp.getBoolean("cover", true)
        set(value) = sp.edit().putBoolean("cover", value).apply()

    var workers: Int
        get() = sp.getInt("workers", 2)
        set(value) = sp.edit().putInt("workers", value).apply()

    var template: String
        get() = sp.getString("template", "{name}") ?: "{name}"
        set(value) = sp.edit().putString("template", value).apply()

    /** 主题模式：0=跟随系统，1=浅色，2=深色（见 ui/theme/ThemeMode）。 */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(value) = sp.edit().putInt("themeMode", value).apply()
}
