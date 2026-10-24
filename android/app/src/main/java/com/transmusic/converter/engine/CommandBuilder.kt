package com.transmusic.converter.engine

import com.transmusic.converter.core.MediaInfo
import com.transmusic.converter.model.AudioFormat
import kotlin.math.abs

/**
 * DSD（.dsf/.dff）相关判定与参数换算。ffmpeg 只有 DSD 解码器，没有编码器，
 * 所以 DSD 只作为输入，输出一律是 PCM 系列格式。
 */
object DsdSupport {
    /** ffmpeg 解码后的采样率（8x/16x/32x）与 DSD 原生采样率（64x/128x/256x）。 */
    val sourceRates: Set<Int> = setOf(
        352800, 705600, 1411200, 2822400, 5644800, 11289600, 22579200,
    )

    fun isDsdSource(codec: String?, sampleRate: Int, path: String? = null): Boolean {
        if (!codec.isNullOrBlank() && codec.startsWith("dsd", ignoreCase = true)) return true
        if (sampleRate in sourceRates) return true
        val name = path?.lowercase() ?: return false
        return name.endsWith(".dsf") || name.endsWith(".dff")
    }

    /** DSD 源默认降到 PCM 常用倍频：DSD64→88.2k、DSD128→176.4k、DSD256→352.8k。 */
    fun pcmTarget(srcSampleRate: Int): Int {
        val factor = if (srcSampleRate > 0) Math.round(srcSampleRate / 44100f) else 0
        return when (factor) {
            8, 64 -> 88200      // DSD64
            16, 128 -> 176400   // DSD128
            32, 256 -> 352800   // DSD256
            else -> when {
                factor <= 8 -> 88200
                factor <= 16 -> 176400
                else -> 352800
            }
        }
    }
}

/**
 * 把「目标格式 + 设置 + 源信息」翻译成 ffmpeg 参数列表。
 * 逻辑与 Windows 版 transmusic/engine.py::build_command 严格一致，
 * 这样同一份源文件在两端得到相同的编码参数。
 */
object CommandBuilder {

    fun nearest(values: List<Int>, target: Int): Int =
        if (values.isEmpty()) target else values.minByOrNull { abs(it - target) } ?: target

    /** 返回 -ar 的取值；0 表示沿用源采样率。 */
    fun resolveSampleRate(
        fmt: AudioFormat,
        request: Int,
        srcSampleRate: Int,
        srcCodec: String = "",
        srcPath: String? = null,
    ): Int {
        if (request > 0) {
            return if (fmt.sampleRates.isNotEmpty() && request !in fmt.sampleRates) {
                nearest(fmt.sampleRates, request)
            } else {
                request
            }
        }
        if (srcSampleRate <= 0) return 0
        var target = srcSampleRate
        if (DsdSupport.isDsdSource(srcCodec, srcSampleRate, srcPath)) {
            // DSD 保持原样等于输出 352.8kHz 以上的巨量 PCM，默认降到 88.2kHz 系
            target = DsdSupport.pcmTarget(srcSampleRate)
        }
        if (fmt.sampleRates.isNotEmpty() && target !in fmt.sampleRates) {
            target = nearest(fmt.sampleRates, target)
        }
        return if (target == srcSampleRate) 0 else target
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
        val sr = resolveSampleRate(fmt, settings.sampleRate, info.sampleRate, info.codec, srcPath)
        val dsd = DsdSupport.isDsdSource(info.codec, info.sampleRate, srcPath)
        if (dsd && sr in 1..96000) {
            // DSD 的噪声整形集中在超声波段，降到低采样率 PCM 前先低通，避免混叠
            cmd += listOf("-af", "lowpass=f=30000")
        }
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
