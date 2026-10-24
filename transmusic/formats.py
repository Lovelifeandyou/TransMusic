"""目标格式注册表。

每个 AudioFormat 描述「如何把一个输入编码成这个格式」：
    codec        ffmpeg 编码器名
    muxer        容器（None 表示按扩展名由 ffmpeg 自动选择）
    bitrates     可选码率（仅失真格式）
    vbr_choices  可选 VBR 档位，值为 ffmpeg 的 -q:a / -compression_level 参数
    sample_rates 该编码器支持的采样率，空表示沿用源采样率
    bit_depths   可选位深（仅无损格式），配合 sample_fmts 映射到 -sample_fmt
    comp_levels  压缩级别范围（无损压缩格式）
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

# 采样率 / 声道 / 位深的「保持原样」哨兵值
KEEP = 0


@dataclass(frozen=True)
class AudioFormat:
    key: str
    label: str
    ext: str
    codec: str
    lossless: bool = False
    muxer: Optional[str] = None
    bitrates: tuple[int, ...] = ()
    vbr_choices: tuple[tuple[str, int], ...] = ()
    sample_rates: tuple[int, ...] = ()
    bit_depths: tuple[int, ...] = ()
    sample_fmts: dict[int, str] = field(default_factory=dict)
    comp_levels: Optional[tuple[int, int]] = None
    comp_default: int = 5
    tags: bool = True
    cover: bool = False
    max_channels: int = 2
    default_bitrate: int = 0
    default_bit_depth: int = 0
    note: str = ""

    # ---- 供界面使用的显示名 ----
    @property
    def display(self) -> str:
        return f"{self.label}  ({'.' + self.ext})"

    def bitrate_choices(self) -> tuple[int, ...]:
        return self.bitrates

    def default_comp(self) -> int:
        return min(max(self.comp_default, self.comp_levels[0]), self.comp_levels[1]) if self.comp_levels else 0


# 常用采样率全集
SR_CD = (44100, 48000)
SR_MP3 = (8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
SR_AAC = (8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000)

MP3_VBR = (
    ("VBR V0（约 245k，最高）", 0),
    ("VBR V1（约 225k）", 1),
    ("VBR V2（约 190k）", 2),
    ("VBR V3（约 175k）", 3),
    ("VBR V4（约 165k）", 4),
    ("VBR V5（约 130k）", 5),
    ("VBR V6（约 115k）", 6),
)

VORBIS_VBR = (
    ("VBR Q10（约 500k）", 10),
    ("VBR Q8（约 256k）", 8),
    ("VBR Q6（约 192k）", 6),
    ("VBR Q5（约 160k）", 5),
    ("VBR Q4（约 128k）", 4),
    ("VBR Q2（约 96k）", 2),
)

OPUS_VBR = (
    ("VBR 192k（音乐）", 192),
    ("VBR 128k（推荐）", 128),
    ("VBR 96k", 96),
    ("VBR 64k（语音）", 64),
    ("VBR 48k（低带宽）", 48),
)

FORMATS: dict[str, AudioFormat] = {
    f.key: f
    for f in [
        AudioFormat(
            key="mp3",
            label="MP3  · 兼容性最强",
            ext="mp3",
            codec="libmp3lame",
            muxer="mp3",
            bitrates=(320, 256, 224, 192, 160, 128, 112, 96, 64, 48, 32),
            vbr_choices=MP3_VBR,
            sample_rates=SR_MP3,
            tags=True,
            cover=True,
            default_bitrate=320,
            note="LAME 编码，支持 CBR/VBR、ID3v2.3 标签与内嵌封面",
        ),
        AudioFormat(
            key="m4a_aac",
            label="AAC / M4A  · 苹果与移动端",
            ext="m4a",
            codec="aac",
            muxer="ipod",
            bitrates=(512, 320, 256, 224, 192, 160, 128, 96, 64, 48, 32),
            sample_rates=SR_AAC,
            tags=True,
            cover=True,
            default_bitrate=256,
            note="原生 AAC-LC，体积小、手机与 Apple 生态通用",
        ),
        AudioFormat(
            key="m4a_alac",
            label="ALAC / M4A  · 苹果无损",
            ext="m4a",
            codec="alac",
            muxer="ipod",
            lossless=True,
            bit_depths=(16, 24),
            sample_fmts={16: "s16p", 24: "s32p"},
            tags=True,
            cover=True,
            default_bit_depth=16,
            note="Apple 无损，可无损还原，支持封面与标签",
        ),
        AudioFormat(
            key="flac",
            label="FLAC  · 无损压缩",
            ext="flac",
            codec="flac",
            lossless=True,
            muxer="flac",
            bit_depths=(16, 24),
            sample_fmts={16: "s16", 24: "s32"},
            comp_levels=(0, 12),
            comp_default=5,
            tags=True,
            cover=True,
            default_bit_depth=16,
            note="无损压缩，压缩级别越高文件越小、编码越慢",
        ),
        AudioFormat(
            key="wav",
            label="WAV  · 未压缩 PCM",
            ext="wav",
            codec="pcm_s16le",
            lossless=True,
            muxer="wav",
            bit_depths=(16, 24, 32),
            sample_fmts={16: "pcm_s16le", 24: "pcm_s24le", 32: "pcm_f32le"},
            tags=False,
            default_bit_depth=16,
            note="编辑/母带首选，16/24 bit 整数、32 bit 浮点",
        ),
        AudioFormat(
            key="aiff",
            label="AIFF  · 苹果未压缩",
            ext="aiff",
            codec="pcm_s16be",
            lossless=True,
            muxer="aiff",
            bit_depths=(16, 24),
            sample_fmts={16: "pcm_s16be", 24: "pcm_s24be"},
            tags=False,
            default_bit_depth=16,
            note="Mac 工作流常用，大端 PCM",
        ),
        AudioFormat(
            key="ogg",
            label="OGG Vorbis  · 开源失真",
            ext="ogg",
            codec="libvorbis",
            muxer="ogg",
            bitrates=(320, 256, 192, 160, 128, 96, 64),
            vbr_choices=VORBIS_VBR,
            sample_rates=(8000, 11025, 16000, 22050, 32000, 44100, 48000),
            tags=True,
            default_bitrate=192,
            note="同码率下优于 MP3，游戏与安卓常用",
        ),
        AudioFormat(
            key="opus",
            label="Opus  · 现代高效",
            ext="opus",
            codec="libopus",
            muxer="opus",
            bitrates=(256, 192, 160, 128, 96, 64, 48, 32, 24, 16),
            vbr_choices=OPUS_VBR,
            sample_rates=(48000,),
            tags=True,
            default_bitrate=128,
            note="低码率最强，一定 48kHz，适合语音与流媒体",
        ),
        AudioFormat(
            key="wma",
            label="WMA  · Windows 生态",
            ext="wma",
            codec="wmav2",
            muxer="asf",
            bitrates=(192, 160, 128, 96, 64, 48, 32),
            sample_rates=SR_CD,
            tags=True,
            default_bitrate=192,
            note="老设备、Windows Media Player 兼容",
        ),
        AudioFormat(
            key="ac3",
            label="AC3  · 杜比数字",
            ext="ac3",
            codec="ac3",
            muxer="ac3",
            bitrates=(640, 448, 384, 320, 256, 192, 128),
            sample_rates=SR_CD,
            tags=False,
            default_bitrate=448,
            note="家庭影院 / DVD 音轨",
        ),
        AudioFormat(
            key="eac3",
            label="E-AC3  · 杜比数字+",
            ext="eac3",
            codec="eac3",
            muxer="eac3",
            bitrates=(1024, 768, 640, 448, 256, 192),
            sample_rates=SR_CD,
            tags=False,
            default_bitrate=640,
            note="比 AC3 更高效，蓝光音轨常用",
        ),
        AudioFormat(
            key="mp2",
            label="MP2  · 广播 / MPEG-1",
            ext="mp2",
            codec="mp2",
            muxer="mp2",
            bitrates=(384, 256, 224, 192, 128, 64),
            sample_rates=SR_CD,
            tags=False,
            default_bitrate=384,
            note="老式广播、电视与 DVB 设备",
        ),
        AudioFormat(
            key="wv",
            label="WavPack  · 无损压缩",
            ext="wv",
            codec="wavpack",
            muxer="wv",
            lossless=True,
            bit_depths=(16, 32),
            sample_fmts={16: "s16p", 32: "s32p"},
            comp_levels=(0, 8),
            comp_default=4,
            tags=True,
            default_bit_depth=16,
            note="无损，编码极快；16bit 或 32bit 整数",
        ),
        AudioFormat(
            key="tta",
            label="TTA  · 实时无损",
            ext="tta",
            codec="tta",
            muxer="tta",
            lossless=True,
            bit_depths=(16, 24),
            sample_fmts={16: "s16", 24: "s32"},
            tags=True,
            default_bit_depth=16,
            note="解码开销极低，老播放器与车载友好",
        ),
        AudioFormat(
            key="spx",
            label="Speex  · 极低码率语音",
            ext="spx",
            codec="libspeex",
            muxer="ogg",
            bitrates=(32, 24, 16, 8),
            sample_rates=(8000, 16000, 32000),
            tags=True,
            max_channels=1,
            default_bitrate=16,
            note="只适合人声，8/16/32kHz 单声道",
        ),
    ]
}

# DSD（Direct Stream Digital）没有对应的 ffmpeg 编码器，只支持「解码」。
# ffmpeg 解码 DSF/DFF 时会先做 8:1 抽取，得到下列 PCM 采样率：
#   DSD64  (2.8224 MHz) → 352800 Hz
#   DSD128 (5.6448 MHz) → 705600 Hz
#   DSD256 (11.2896 MHz) → 1411200 Hz
DSD_SOURCE_RATES = (352800, 705600, 1411200, 2822400, 5644800, 11289600, 22579200)
DSD_EXTS = (".dsf", ".dff")

# 界面下拉框顺序：常用在前
FORMAT_ORDER = [
    "mp3", "m4a_aac", "flac", "wav", "m4a_alac", "ogg", "opus",
    "aiff", "wma", "wv", "tta", "ac3", "eac3", "mp2", "spx",
]


def get_format(key: str) -> AudioFormat:
    return FORMATS[key]


def format_by_extension(ext: str) -> Optional[AudioFormat]:
    ext = ext.lower().lstrip(".")
    for key in FORMAT_ORDER:
        if FORMATS[key].ext == ext:
            return FORMATS[key]
    return None


# 界面上「预设」按钮：一键套用常用参数组合
@dataclass(frozen=True)
class Preset:
    key: str
    label: str
    format_key: str
    bitrate: int = 0
    vbr_index: int = -1          # -1 表示 CBR
    sample_rate: int = KEEP
    channels: int = KEEP
    bit_depth: int = 0
    comp_level: int = -1
    hint: str = ""


PRESETS: tuple[Preset, ...] = (
    Preset("dsd_flac", "DSD → FLAC 归档", "flac", sample_rate=88200, bit_depth=24, comp_level=8,
           hint="把 .dsf/.dff 解成 88.2kHz/24bit 无损；DSD128 建议手动改 176.4kHz"),
    Preset("dsd_wav", "DSD → WAV 24bit", "wav", sample_rate=88200, bit_depth=24,
           hint="给不支持 DSD 的播放器/音频编辑器"),
    Preset("dsd_mp3", "DSD → MP3 320", "mp3", bitrate=320,
           hint="车载与便携播放器通用"),
    Preset("hq_mp3", "MP3 320 通用", "mp3", bitrate=320,
           hint="最保险的互通格式，任何设备都能放"),
    Preset("vbr_mp3", "MP3 V0 高音质", "mp3", vbr_index=0,
           hint="VBR 接近透明音质，体积略小于 320 CBR"),
    Preset("lossless", "FLAC 无损归档", "flac", bit_depth=16, comp_level=5,
           hint="无损保存，体积约为 WAV 的 60%"),
    Preset("archive24", "FLAC 24bit 母带", "flac", bit_depth=24, comp_level=8,
           hint="保留 24bit 动态，适合母带与 Hi-Res"),
    Preset("cd_wav", "CD 级 WAV", "wav", sample_rate=44100, channels=2, bit_depth=16,
           hint="44.1kHz/16bit 立体声，刻盘与编辑标准"),
    Preset("mobile_aac", "AAC 256 手机", "m4a_aac", bitrate=256,
           hint="iPhone、安卓默认生态，体积小"),
    Preset("voice_opus", "Opus 48k 语音", "opus", bitrate=48, channels=1,
           hint="播客/会议录音，体积极小"),
    Preset("ringtone", "铃声片段参数", "m4a_aac", bitrate=192, channels=1, sample_rate=44100,
           hint="配合裁剪使用；转换后自行截取 30 秒"),
)


def preset_by_key(key: str) -> Optional[Preset]:
    for p in PRESETS:
        if p.key == key:
            return p
    return None
