package com.transmusic.converter.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.transmusic.converter.core.SavedSettings
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.model.AudioFormat
import com.transmusic.converter.model.Formats

/** 质量下拉里的一项（CBR 码率 / VBR 档位 / 无损位深，三者只会命中一个）。 */
data class QualityItem(
    val label: String,
    val bitrate: Int = 0,
    val vbrIndex: Int = -1,
    val bitDepth: Int = 0,
)

fun depthLabel(depth: Int): String = when (depth) {
    16 -> "16 bit · CD 级"
    24 -> "24 bit · Hi-Res"
    32 -> "32 bit · 高精度"
    else -> "$depth bit"
}

fun buildQuality(fmt: AudioFormat): List<QualityItem> {
    val items = mutableListOf<QualityItem>()
    if (fmt.lossless) {
        fmt.bitDepths.forEach { items += QualityItem(depthLabel(it), bitDepth = it) }
    } else {
        fmt.bitrates.forEach { items += QualityItem("CBR $it kbps", bitrate = it) }
        fmt.vbrLabels.forEachIndexed { index, label -> items += QualityItem(label, vbrIndex = index) }
    }
    return items.ifEmpty { listOf(QualityItem("默认")) }
}

fun defaultQualityIndex(fmt: AudioFormat, items: List<QualityItem>): Int {
    val index = if (fmt.lossless) {
        items.indexOfFirst { it.bitDepth == fmt.defaultBitDepth }
    } else {
        items.indexOfFirst { it.bitrate == fmt.defaultBitrate }
    }
    return if (index >= 0) index else 0
}

fun sampleRateOptions(fmt: AudioFormat): List<Int> = listOf(0) + if (fmt.sampleRates.isNotEmpty()) {
    fmt.sampleRates
} else {
    listOf(22050, 44100, 48000, 88200, 96000, 176400, 192000)
}

fun sampleRateLabel(sampleRate: Int): String =
    if (sampleRate == 0) "保持原样" else "$sampleRate Hz"

fun channelOptions(fmt: AudioFormat): List<Int> =
    if (fmt.maxChannels <= 1) listOf(1) else listOf(0, 1, 2)

fun channelLabel(channels: Int): String = when (channels) {
    0 -> "保持原样"
    1 -> "单声道"
    2 -> "立体声"
    else -> "$channels 声道"
}

/** 摘要卡片上的一行说明，例如「MP3 · CBR 320 kbps」。 */
fun summarize(fmt: AudioFormat, quality: QualityItem?): String {
    val detail = quality?.label ?: when {
        fmt.lossless -> "${fmt.defaultBitDepth} bit"
        else -> "CBR ${fmt.defaultBitrate} kbps"
    }
    return "${fmt.label.substringBefore("  ·")} · $detail"
}

/**
 * 界面上的转换参数状态（MD3E 侧），双向绑定到设置面板控件。
 * 与 [ConvertSettings] 的转换由 [toSettings] 完成，保证和 Windows 端参数语义一致。
 */
@Stable
class ConvertSettingsState(saved: SavedSettings) {

    var formatKey by mutableStateOf(if (Formats.find(saved.formatKey) != null) saved.formatKey else "mp3")
        private set

    var qualityIndex by mutableIntStateOf(saved.qualityIndex)
    var sampleRate by mutableIntStateOf(saved.sampleRate)
    var channels by mutableIntStateOf(saved.channels)
    var compLevel by mutableIntStateOf(saved.compLevel)
    var keepMetadata by mutableStateOf(saved.keepMeta)
    var keepCover by mutableStateOf(saved.keepCover)
    var workers by mutableIntStateOf(saved.workers.coerceIn(1, 8))

    val format: AudioFormat get() = Formats.find(formatKey) ?: Formats.ORDERED.first()

    val qualityItems: List<QualityItem> get() = buildQuality(format)

    val quality: QualityItem?
        get() = qualityItems.getOrNull(qualityIndex.coerceIn(0, qualityItems.size - 1))

    val sampleRates: List<Int> get() = sampleRateOptions(format)

    val channelList: List<Int> get() = channelOptions(format)

    val compRange: IntRange? get() = format.compLevels?.let { it.first..it.second }

    fun selectFormat(key: String) {
        val next = Formats.find(key) ?: return
        if (next.key == formatKey) return
        formatKey = next.key
        val items = buildQuality(next)
        qualityIndex = defaultQualityIndex(next, items)
        sampleRate = 0
        channels = if (next.maxChannels <= 1) 1 else 0
        compLevel = next.compLevels?.let { next.defaultComp() } ?: -1
    }

    fun selectQuality(index: Int) {
        qualityIndex = index.coerceIn(0, (qualityItems.size - 1).coerceAtLeast(0))
    }

    fun defaultCompLevel(): Int = compRange?.let { format.defaultComp().coerceIn(it.first, it.last) } ?: -1

    fun toSettings(): ConvertSettings {
        val item = quality
        val fmt = format
        val comps = fmt.compLevels
        return ConvertSettings(
            formatKey = fmt.key,
            bitrate = item?.bitrate?.takeIf { it > 0 } ?: fmt.defaultBitrate,
            vbrIndex = item?.vbrIndex ?: -1,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = if (fmt.lossless) {
                item?.bitDepth ?: fmt.defaultBitDepth
            } else {
                0
            },
            compLevel = if (comps != null) compLevel.coerceIn(comps.first, comps.second) else -1,
            keepMetadata = keepMetadata,
            keepCover = keepCover,
        )
    }
}
