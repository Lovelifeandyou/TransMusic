package com.transmusic.converter.engine

import com.transmusic.converter.core.MediaInfo
import com.transmusic.converter.model.AudioFormat
import kotlin.math.abs

/**
 * 把「目标格式 + 设置 + 源信息」翻译成 ffmpeg 参数列表。
 * 逻辑与 Windows 版 transmusic/engine.py::build_command 严格一致，
 * 这样同一份源文件在两端得到相同的编码参数。
 */
object CommandBuilder {

    fun nearest(values: List<Int>, target: Int): Int =
        if (values.isEmpty()) target else values.minByOrNull { abs(it - target) } ?: target

    /** 返回 -ar 的取值；0 表示沿用源采样率。 */
    fun resolveSampleRate(fmt: AudioFormat, request: Int, srcSampleRate: Int): Int = when {
        request > 0 ->
            if (fmt.sampleRates.isNotEmpty() && request !in fmt.sampleRates) nearest(fmt.sampleRates, request) else request
        srcSampleRate > 0 && fmt.sampleRates.isNotEmpty() && srcSampleRate !in fmt.sampleRates ->
            nearest(fmt.sampleRates, srcSampleRate)
        else -> 0
    }

    fun resolveChannels(fmt: AudioFormat, request: Int): Int = when {
        fmt.maxChannels in 1..(request - 1) -> fmt.maxChannels
        fmt.maxChannels == 1 -> 1
        else -> request
    }

    fun resolveBitDepth(fmt: AudioFormat, request: Int): Int = when {
        fmt.sampleFmts.isEmpty() -> 0
        fmt.sampleFmts.containsKey(request) -> request
        fmt.sampleFmts.containsKey(fmt.defaultBitDepth) -> fmt.defaultBitDepth
        else -> fmt.sampleFmts.keys.first()
    }

    fun build(
        srcPath: String,
        dstPath: String,
        fmt: AudioFormat,
        settings: ConvertSettings,
        info: MediaInfo = MediaInfo(),
    ): List<String> {
        val cmd = mutableListOf(
            "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
            "-i", srcPath,
        )

        val copyCover = fmt.cover && settings.keepCover && info.hasCover
        cmd += listOf("-map", "0:a:0")
        if (copyCover) {
            cmd += listOf("-map", "0:v:0?", "-c:v", "copy", "-disposition:v", "attached_pic")
        } else {
            cmd += listOf("-vn")
        }
        cmd += listOf("-sn", "-dn")

        // ---- 编码器 ----
        val depth = if (fmt.lossless) resolveBitDepth(fmt, settings.bitDepth) else 0
        var codec = fmt.codec
        if (fmt.lossless && fmt.sampleFmts.isNotEmpty() && depth > 0) {
            val mapped = fmt.sampleFmts[depth]
            if (mapped != null && mapped.startsWith("pcm_")) codec = mapped
        }
        cmd += listOf("-c:a", codec)

        if (fmt.lossless && fmt.sampleFmts.isNotEmpty() && depth > 0 && !codec.startsWith("pcm_")) {
            cmd += listOf("-sample_fmt", fmt.sampleFmts.getValue(depth))
        }

        // ---- 质量 ----
        if (fmt.lossless) {
            val levels = fmt.compLevels
            if (levels != null) {
                val level = (if (settings.compLevel >= 0) settings.compLevel else fmt.defaultComp())
                    .coerceIn(levels.first, levels.second)
                cmd += listOf("-compression_level", level.toString())
            }
        } else if (settings.vbrIndex >= 0 && fmt.vbrValues.isNotEmpty()) {
            val q = fmt.vbrValues[minOf(settings.vbrIndex, fmt.vbrValues.size - 1)]
            if (fmt.key == "opus") {
                cmd += listOf("-b:a", "${q}k", "-vbr", "on")
            } else {
                cmd += listOf("-q:a", q.toString())
            }
        } else {
            val br = if (settings.bitrate > 0) settings.bitrate else fmt.defaultBitrate
            if (br > 0) cmd += listOf("-b:a", "${br}k")
        }

        if (fmt.key == "opus") {
            cmd += listOf("-compression_level", "10", "-application", "audio")
        }

        // ---- 采样率 / 声道 ----
        val sr = resolveSampleRate(fmt, settings.sampleRate, info.sampleRate)
        if (sr > 0) cmd += listOf("-ar", sr.toString())
        val ch = resolveChannels(fmt, settings.channels)
        if (ch > 0) cmd += listOf("-ac", ch.toString())

        // ---- 元数据 / 封面 ----
        if (settings.keepMetadata && fmt.tags) {
            cmd += listOf("-map_metadata", "0")
        } else {
            cmd += listOf("-map_metadata", "-1")
        }
        cmd += listOf("-map_chapters", "-1")
        if (fmt.key == "mp3") {
            cmd += listOf("-id3v2_version", "3", "-write_id3v1", "1")
        }
        if (fmt.muxer == "ipod") {
            cmd += listOf("-movflags", "+faststart")
        }

        cmd += dstPath
        return cmd
    }

    /** 便于排查：转成可直接粘贴到终端的一行命令（调试用）。 */
    fun toCommandLine(args: List<String>): String =
        args.joinToString(" ") { if (it.contains(' ') || it.contains('\\')) "\"$it\"" else it }
}
