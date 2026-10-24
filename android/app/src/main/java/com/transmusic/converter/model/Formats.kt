package com.transmusic.converter.model

/*
 * ⚠ 本文件由 tools/gen_android_formats.py 依据 Windows 版 transmusic/formats.py 自动生成。
 *   请勿手工修改：改格式参数请改 Python 端，然后重新运行生成脚本（tests/formats_parity_test.py 会检查漂移）。
 */

/** 一个目标格式的完整编码规格。 */
data class AudioFormat(
    val key: String,
    val label: String,
    val ext: String,
    val codec: String,
    val lossless: Boolean = false,
    val muxer: String? = null,
    val bitrates: List<Int> = emptyList(),
    /** VBR 档位显示名，与 [vbrValues] 一一对应。 */
    val vbrLabels: List<String> = emptyList(),
    /** 对应 ffmpeg 的 -q:a / 码率参数值。 */
    val vbrValues: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
    val bitDepths: List<Int> = emptyList(),
    /** 位深 → -sample_fmt（无损格式用，值以 pcm_ 开头时直接当编码器名）。 */
    val sampleFmts: Map<Int, String> = emptyMap(),
    val compLevels: Pair<Int, Int>? = null,
    val compDefault: Int = 5,
    val tags: Boolean = true,
    val cover: Boolean = false,
    val maxChannels: Int = 2,
    val defaultBitrate: Int = 0,
    val defaultBitDepth: Int = 0,
    val note: String = "",
) {
    val display: String get() = "$label  (.$ext)"
    fun defaultComp(): Int = compLevels?.let { compDefault.coerceIn(it.first, it.second) } ?: 0
}

/** 预设参数组合（一键套用）。 */
data class Preset(
    val key: String,
    val label: String,
    val formatKey: String,
    val bitrate: Int = 0,
    val vbrIndex: Int = -1,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val compLevel: Int = -1,
    val hint: String = "",
)

object Formats {
    /** 全部可选目标格式。 */
    val ALL: List<AudioFormat> = listOf(
        AudioFormat(
            key = "mp3",
            label = "MP3  · 兼容性最强",
            ext = "mp3",
            codec = "libmp3lame",
            muxer = "mp3",
            bitrates = listOf(320, 256, 224, 192, 160, 128, 112, 96, 64, 48, 32),
            vbrLabels = listOf("VBR V0（约 245k，最高）", "VBR V1（约 225k）", "VBR V2（约 190k）", "VBR V3（约 175k）", "VBR V4（约 165k）", "VBR V5（约 130k）", "VBR V6（约 115k）"),
            vbrValues = listOf(0, 1, 2, 3, 4, 5, 6),
            sampleRates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000),
            cover = true,
            defaultBitrate = 320,
            note = "LAME 编码，支持 CBR/VBR、ID3v2.3 标签与内嵌封面",
        ),
        AudioFormat(
            key = "m4a_aac",
            label = "AAC / M4A  · 苹果与移动端",
            ext = "m4a",
            codec = "aac",
            muxer = "ipod",
            bitrates = listOf(512, 320, 256, 224, 192, 160, 128, 96, 64, 48, 32),
            sampleRates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000),
            cover = true,
            defaultBitrate = 256,
            note = "原生 AAC-LC，体积小、手机与 Apple 生态通用",
        ),
        AudioFormat(
            key = "flac",
            label = "FLAC  · 无损压缩",
            ext = "flac",
            codec = "flac",
            lossless = true,
            muxer = "flac",
            bitDepths = listOf(16, 24),
            sampleFmts = mapOf(16 to "s16", 24 to "s32"),
            compLevels = Pair(0, 12),
            compDefault = 5,
            cover = true,
            defaultBitDepth = 16,
            note = "无损压缩，压缩级别越高文件越小、编码越慢",
        ),
        AudioFormat(
            key = "wav",
            label = "WAV  · 未压缩 PCM",
            ext = "wav",
            codec = "pcm_s16le",
            lossless = true,
            muxer = "wav",
            bitDepths = listOf(16, 24, 32),
            sampleFmts = mapOf(16 to "pcm_s16le", 24 to "pcm_s24le", 32 to "pcm_f32le"),
            tags = false,
            defaultBitDepth = 16,
            note = "编辑/母带首选，16/24 bit 整数、32 bit 浮点",
        ),
        AudioFormat(
            key = "m4a_alac",
            label = "ALAC / M4A  · 苹果无损",
            ext = "m4a",
            codec = "alac",
            lossless = true,
            muxer = "ipod",
            bitDepths = listOf(16, 24),
            sampleFmts = mapOf(16 to "s16p", 24 to "s32p"),
            cover = true,
            defaultBitDepth = 16,
            note = "Apple 无损，可无损还原，支持封面与标签",
        ),
        AudioFormat(
            key = "ogg",
            label = "OGG Vorbis  · 开源失真",
            ext = "ogg",
            codec = "libvorbis",
            muxer = "ogg",
            bitrates = listOf(320, 256, 192, 160, 128, 96, 64),
            vbrLabels = listOf("VBR Q10（约 500k）", "VBR Q8（约 256k）", "VBR Q6（约 192k）", "VBR Q5（约 160k）", "VBR Q4（约 128k）", "VBR Q2（约 96k）"),
            vbrValues = listOf(10, 8, 6, 5, 4, 2),
            sampleRates = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000),
            defaultBitrate = 192,
            note = "同码率下优于 MP3，游戏与安卓常用",
        ),
        AudioFormat(
            key = "opus",
            label = "Opus  · 现代高效",
            ext = "opus",
            codec = "libopus",
            muxer = "opus",
            bitrates = listOf(256, 192, 160, 128, 96, 64, 48, 32, 24, 16),
            vbrLabels = listOf("VBR 192k（音乐）", "VBR 128k（推荐）", "VBR 96k", "VBR 64k（语音）", "VBR 48k（低带宽）"),
            vbrValues = listOf(192, 128, 96, 64, 48),
            sampleRates = listOf(48000),
            defaultBitrate = 128,
            note = "低码率最强，一定 48kHz，适合语音与流媒体",
        ),
        AudioFormat(
            key = "aiff",
            label = "AIFF  · 苹果未压缩",
            ext = "aiff",
            codec = "pcm_s16be",
            lossless = true,
            muxer = "aiff",
            bitDepths = listOf(16, 24),
            sampleFmts = mapOf(16 to "pcm_s16be", 24 to "pcm_s24be"),
            tags = false,
            defaultBitDepth = 16,
            note = "Mac 工作流常用，大端 PCM",
        ),
        AudioFormat(
            key = "wma",
            label = "WMA  · Windows 生态",
            ext = "wma",
            codec = "wmav2",
            muxer = "asf",
            bitrates = listOf(192, 160, 128, 96, 64, 48, 32),
            sampleRates = listOf(44100, 48000),
            defaultBitrate = 192,
            note = "老设备、Windows Media Player 兼容",
        ),
        AudioFormat(
            key = "wv",
            label = "WavPack  · 无损压缩",
            ext = "wv",
            codec = "wavpack",
            lossless = true,
            muxer = "wv",
            bitDepths = listOf(16, 32),
            sampleFmts = mapOf(16 to "s16p", 32 to "s32p"),
            compLevels = Pair(0, 8),
            compDefault = 4,
            defaultBitDepth = 16,
            note = "无损，编码极快；16bit 或 32bit 整数",
        ),
        AudioFormat(
            key = "tta",
            label = "TTA  · 实时无损",
            ext = "tta",
            codec = "tta",
            lossless = true,
            muxer = "tta",
            bitDepths = listOf(16, 24),
            sampleFmts = mapOf(16 to "s16", 24 to "s32"),
            defaultBitDepth = 16,
            note = "解码开销极低，老播放器与车载友好",
        ),
        AudioFormat(
            key = "ac3",
            label = "AC3  · 杜比数字",
            ext = "ac3",
            codec = "ac3",
            muxer = "ac3",
            bitrates = listOf(640, 448, 384, 320, 256, 192, 128),
            sampleRates = listOf(44100, 48000),
            tags = false,
            defaultBitrate = 448,
            note = "家庭影院 / DVD 音轨",
        ),
        AudioFormat(
            key = "eac3",
            label = "E-AC3  · 杜比数字+",
            ext = "eac3",
            codec = "eac3",
            muxer = "eac3",
            bitrates = listOf(1024, 768, 640, 448, 256, 192),
            sampleRates = listOf(44100, 48000),
            tags = false,
            defaultBitrate = 640,
            note = "比 AC3 更高效，蓝光音轨常用",
        ),
        AudioFormat(
            key = "mp2",
            label = "MP2  · 广播 / MPEG-1",
            ext = "mp2",
            codec = "mp2",
            muxer = "mp2",
            bitrates = listOf(384, 256, 224, 192, 128, 64),
            sampleRates = listOf(44100, 48000),
            tags = false,
            defaultBitrate = 384,
            note = "老式广播、电视与 DVB 设备",
        ),
        AudioFormat(
            key = "spx",
            label = "Speex  · 极低码率语音",
            ext = "spx",
            codec = "libspeex",
            muxer = "ogg",
            bitrates = listOf(32, 24, 16, 8),
            sampleRates = listOf(8000, 16000, 32000),
            maxChannels = 1,
            defaultBitrate = 16,
            note = "只适合人声，8/16/32kHz 单声道",
        ),
    )

    private val byKey: Map<String, AudioFormat> = ALL.associateBy { it.key }

    /** 界面下拉顺序（常用在前）。 */
    val ORDER: List<String> = listOf("mp3", "m4a_aac", "flac", "wav", "m4a_alac", "ogg", "opus", "aiff", "wma", "wv", "tta", "ac3", "eac3", "mp2", "spx")

    /** 按界面顺序排列的格式。 */
    val ORDERED: List<AudioFormat> = ORDER.map { byKey.getValue(it) }

    operator fun get(key: String): AudioFormat = byKey.getValue(key)

    fun find(key: String): AudioFormat? = byKey[key]
}

object Presets {
    val ALL: List<Preset> = listOf(
        Preset(
            key = "dsd_flac",
            label = "DSD → FLAC 归档",
            formatKey = "flac",
            bitrate = 0,
            vbrIndex = -1,
            sampleRate = 88200,
            channels = 0,
            bitDepth = 24,
            compLevel = 8,
            hint = "把 .dsf/.dff 解成 88.2kHz/24bit 无损；DSD128 建议手动改 176.4kHz",
        ),
        Preset(
            key = "dsd_wav",
            label = "DSD → WAV 24bit",
            formatKey = "wav",
            bitrate = 0,
            vbrIndex = -1,
            sampleRate = 88200,
            channels = 0,
            bitDepth = 24,
            compLevel = -1,
            hint = "给不支持 DSD 的播放器/音频编辑器",
        ),
        Preset(
            key = "dsd_mp3",
            label = "DSD → MP3 320",
            formatKey = "mp3",
            bitrate = 320,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 0,
            bitDepth = 0,
            compLevel = -1,
            hint = "车载与便携播放器通用",
        ),
        Preset(
            key = "hq_mp3",
            label = "MP3 320 通用",
            formatKey = "mp3",
            bitrate = 320,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 0,
            bitDepth = 0,
            compLevel = -1,
            hint = "最保险的互通格式，任何设备都能放",
        ),
        Preset(
            key = "vbr_mp3",
            label = "MP3 V0 高音质",
            formatKey = "mp3",
            bitrate = 0,
            vbrIndex = 0,
            sampleRate = 0,
            channels = 0,
            bitDepth = 0,
            compLevel = -1,
            hint = "VBR 接近透明音质，体积略小于 320 CBR",
        ),
        Preset(
            key = "lossless",
            label = "FLAC 无损归档",
            formatKey = "flac",
            bitrate = 0,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 0,
            bitDepth = 16,
            compLevel = 5,
            hint = "无损保存，体积约为 WAV 的 60%",
        ),
        Preset(
            key = "archive24",
            label = "FLAC 24bit 母带",
            formatKey = "flac",
            bitrate = 0,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 0,
            bitDepth = 24,
            compLevel = 8,
            hint = "保留 24bit 动态，适合母带与 Hi-Res",
        ),
        Preset(
            key = "cd_wav",
            label = "CD 级 WAV",
            formatKey = "wav",
            bitrate = 0,
            vbrIndex = -1,
            sampleRate = 44100,
            channels = 2,
            bitDepth = 16,
            compLevel = -1,
            hint = "44.1kHz/16bit 立体声，刻盘与编辑标准",
        ),
        Preset(
            key = "mobile_aac",
            label = "AAC 256 手机",
            formatKey = "m4a_aac",
            bitrate = 256,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 0,
            bitDepth = 0,
            compLevel = -1,
            hint = "iPhone、安卓默认生态，体积小",
        ),
        Preset(
            key = "voice_opus",
            label = "Opus 48k 语音",
            formatKey = "opus",
            bitrate = 48,
            vbrIndex = -1,
            sampleRate = 0,
            channels = 1,
            bitDepth = 0,
            compLevel = -1,
            hint = "播客/会议录音，体积极小",
        ),
        Preset(
            key = "ringtone",
            label = "铃声片段参数",
            formatKey = "m4a_aac",
            bitrate = 192,
            vbrIndex = -1,
            sampleRate = 44100,
            channels = 1,
            bitDepth = 0,
            compLevel = -1,
            hint = "配合裁剪使用；转换后自行截取 30 秒",
        ),
    )
}

/** 可识别的音频/视频扩展名（用于文件选择过滤）。 */
object AudioExts {
    val SET: Set<String> = setOf(
        ".3gp", ".aac", ".ac3", ".aif", ".aifc", ".aiff", ".alac", ".amr", ".ape", ".au", ".avi", ".caf", ".dff", ".dsf", ".dts", ".eac3", ".flac", ".flv", ".m2ts", ".m3u", ".m4a", ".m4b", ".mka", ".mkv", ".mov", ".mp1", ".mp2", ".mp3", ".mp4", ".mpc", ".oga", ".ogg", ".opus", ".snd", ".spx", ".ts", ".tta", ".voc", ".wav", ".wavpack", ".webm", ".wma", ".wv"
    )

    fun isAudioLike(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return SET.contains(name.substring(dot).lowercase())
    }
}
