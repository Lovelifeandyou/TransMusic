package com.transmusic.converter.core

import android.net.Uri

/** 任务状态（与 Windows 版一一对应）。 */
enum class JobStatus(val text: String) {
    WAITING("等待"),
    RUNNING("转换中"),
    DONE("完成"),
    FAILED("失败"),
    SKIPPED("已跳过"),
    CANCELED("已取消"),
}

/** 源文件媒体信息。 */
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
) {
    val durationText: String
        get() {
            if (durationMs <= 0) return "--:--"
            val total = (durationMs + 500) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

    val bitrateText: String
        get() = when {
            bitrate <= 0 -> "-"
            bitrate >= 1000 -> "${bitrate / 1000} kbps"
            else -> "$bitrate bps"
        }

    val sampleRateText: String
        get() = if (sampleRate <= 0) "-" else {
            val khz = sampleRate / 1000.0
            if (khz == khz.toInt().toDouble()) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
        }

    val channelsText: String
        get() = when {
            channels <= 0 -> "-"
            channels == 1 -> "单声道"
            channels == 2 -> "立体声"
            else -> "$channels 声道"
        }
}

/** 一条转换任务。 */
data class Job(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val info: MediaInfo = MediaInfo(),
    val status: JobStatus = JobStatus.WAITING,
    val progress: Float = 0f,
    val message: String = "",
    val outName: String? = null,
    val elapsedMs: Long = 0L,
) {
    val title: String get() = displayName.substringBeforeLast('.', displayName)

    val metaText: String
        get() {
            val rawCodec = info.codec.ifBlank { displayName.substringAfterLast('.', "").uppercase() }
            // ffmpeg 解 DSD 会报 dsd_lsbf_planar 之类，统一显示成 DSD
            val codec = if (rawCodec.startsWith("dsd", ignoreCase = true) ||
                rawCodec.equals("DSF", ignoreCase = true) || rawCodec.equals("DFF", ignoreCase = true)
            ) "DSD" else rawCodec
            return listOf(codec, info.durationText, info.bitrateText, info.sampleRateText, info.channelsText)
                .filter { it.isNotBlank() && it != "-" }
                .joinToString(" · ")
        }

    val progressText: String
        get() = when (status) {
            JobStatus.DONE -> "100%"
            JobStatus.RUNNING -> "${progress.toInt()}%"
            else -> ""
        }

    val statusText: String
        get() = when {
            status == JobStatus.FAILED && message.isNotBlank() -> "失败：${message.take(40)}"
            status == JobStatus.DONE && message.isNotBlank() -> message
            else -> status.text
        }
}
