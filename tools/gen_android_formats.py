"""从 Python 端格式注册表生成 Android 端 Formats.kt。

单一数据源：Windows 桌面版与 Android 版共用同一份格式/参数定义，
避免两端「能转的格式」和「质量档位」互相漂移。

用法：python tools/gen_android_formats.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from transmusic.engine import AUDIO_EXTS  # noqa: E402
from transmusic.formats import FORMATS, FORMAT_ORDER, PRESETS  # noqa: E402

OUT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "transmusic" / "converter" / "model" / "Formats.kt"

HEADER = '''package com.transmusic.converter.model

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
'''


def kotlin_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def opt_str(s: str | None) -> str:
    return "null" if s is None else kotlin_str(s)


def int_list(values) -> str:
    return "listOf(" + ", ".join(str(v) for v in values) + ")" if values else "emptyList()"


def str_list(values) -> str:
    return "listOf(" + ", ".join(kotlin_str(v) for v in values) + ")" if values else "emptyList()"


def fmt_block(f) -> str:
    if f.sample_fmts:
        fmts = "mapOf(" + ", ".join(f"{k} to {kotlin_str(v)}" for k, v in f.sample_fmts.items()) + ")"
    else:
        fmts = "emptyMap()"
    comp = f"Pair({f.comp_levels[0]}, {f.comp_levels[1]})" if f.comp_levels else "null"
    lines = [
        f"        AudioFormat(",
        f"            key = {kotlin_str(f.key)},",
        f"            label = {kotlin_str(f.label)},",
        f"            ext = {kotlin_str(f.ext)},",
        f"            codec = {kotlin_str(f.codec)},",
    ]
    if f.lossless:
        lines.append("            lossless = true,")
    if f.muxer:
        lines.append(f"            muxer = {kotlin_str(f.muxer)},")
    if f.bitrates:
        lines.append(f"            bitrates = {int_list(f.bitrates)},")
    if f.vbr_choices:
        lines.append(f"            vbrLabels = {str_list([c[0] for c in f.vbr_choices])},")
        lines.append(f"            vbrValues = {int_list([c[1] for c in f.vbr_choices])},")
    if f.sample_rates:
        lines.append(f"            sampleRates = {int_list(f.sample_rates)},")
    if f.bit_depths:
        lines.append(f"            bitDepths = {int_list(f.bit_depths)},")
    if f.sample_fmts:
        lines.append(f"            sampleFmts = {fmts},")
    if f.comp_levels:
        lines.append(f"            compLevels = {comp},")
        lines.append(f"            compDefault = {f.comp_default},")
    if not f.tags:
        lines.append("            tags = false,")
    if f.cover:
        lines.append("            cover = true,")
    if f.max_channels != 2:
        lines.append(f"            maxChannels = {f.max_channels},")
    if f.default_bitrate:
        lines.append(f"            defaultBitrate = {f.default_bitrate},")
    if f.default_bit_depth:
        lines.append(f"            defaultBitDepth = {f.default_bit_depth},")
    if f.note:
        lines.append(f"            note = {kotlin_str(f.note)},")
    lines.append("        ),")
    return "\n".join(lines)


def main() -> None:
    parts = [HEADER]
    parts.append("    /** 全部可选目标格式。 */\n    val ALL: List<AudioFormat> = listOf(\n")
    parts.append("\n".join(fmt_block(FORMATS[k]) for k in FORMAT_ORDER))
    parts.append("\n    )\n\n")
    # 注意：byKey 必须在 ORDERED 之前声明，否则对象初始化顺序会导致 null
    parts.append("    private val byKey: Map<String, AudioFormat> = ALL.associateBy { it.key }\n\n")
    parts.append("    /** 界面下拉顺序（常用在前）。 */\n    val ORDER: List<String> = listOf(")
    parts.append(", ".join(kotlin_str(k) for k in FORMAT_ORDER))
    parts.append(")\n\n")
    parts.append("    /** 按界面顺序排列的格式。 */\n"
                 "    val ORDERED: List<AudioFormat> = ORDER.map { byKey.getValue(it) }\n\n")
    parts.append("    operator fun get(key: String): AudioFormat = byKey.getValue(key)\n\n")
    parts.append("    fun find(key: String): AudioFormat? = byKey[key]\n")
    parts.append("}\n\n")

    parts.append("object Presets {\n    val ALL: List<Preset> = listOf(\n")
    for p in PRESETS:
        parts.append(
            "        Preset(\n"
            f"            key = {kotlin_str(p.key)},\n"
            f"            label = {kotlin_str(p.label)},\n"
            f"            formatKey = {kotlin_str(p.format_key)},\n"
            f"            bitrate = {p.bitrate},\n"
            f"            vbrIndex = {p.vbr_index},\n"
            f"            sampleRate = {p.sample_rate},\n"
            f"            channels = {p.channels},\n"
            f"            bitDepth = {p.bit_depth},\n"
            f"            compLevel = {p.comp_level},\n"
            f"            hint = {kotlin_str(p.hint)},\n"
            "        ),\n"
        )
    parts.append("    )\n}\n\n")

    exts = sorted(AUDIO_EXTS)
    parts.append("/** 可识别的音频/视频扩展名（用于文件选择过滤）。 */\nobject AudioExts {\n")
    parts.append("    val SET: Set<String> = setOf(\n        ")
    parts.append(", ".join(kotlin_str(e) for e in exts))
    parts.append("\n    )\n\n    fun isAudioLike(name: String): Boolean {\n"
                 "        val dot = name.lastIndexOf('.')\n"
                 "        if (dot < 0) return false\n"
                 "        return SET.contains(name.substring(dot).lowercase())\n"
                 "    }\n}\n")

    text = "".join(parts)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, "utf-8")
    print(f"已生成 {OUT}")
    print(f"  格式 {len(FORMAT_ORDER)} 个，预设 {len(PRESETS)} 个，扩展名 {len(exts)} 个，{len(text.splitlines())} 行")


if __name__ == "__main__":
    main()
