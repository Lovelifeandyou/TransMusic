package com.transmusic.converter.core

/**
 * 仅用于一致性测试的 MediaInfo 桩：
 * 真实 Android 端的 MediaInfo 在 core/JobModels.kt（附带 Uri 等 Android 依赖），
 * 这里只保留 CommandBuilder 需要的字段，便于脱离 Android SDK 编译。
 */
data class MediaInfo(
    val durationMs: Long = 0L,
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val codec: String = "",
    val hasCover: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val ok: Boolean = true,
    val error: String = "",
)
