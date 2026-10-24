package com.transmusic.converter.engine

/** 一次转换的全部参数（与 Windows 版 ConvertSettings 对应）。 */
data class ConvertSettings(
    val formatKey: String = "mp3",
    val bitrate: Int = 320,
    val vbrIndex: Int = -1,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val compLevel: Int = -1,
    val keepMetadata: Boolean = true,
    val keepCover: Boolean = true,
)
