"""端到端自检：把所有目标格式跑一遍，核对输出参数是否正确。

用法：python tests/smoke_test.py
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from transmusic import engine  # noqa: E402
from transmusic.engine import ConvertSettings  # noqa: E402
from transmusic.formats import FORMAT_ORDER, FORMATS, PRESETS  # noqa: E402

OUT = ROOT / "tests" / "_out"
SAMPLES = ROOT / "samples"


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, errors="replace")


def make_sources() -> tuple[Path, Path]:
    SAMPLES.mkdir(exist_ok=True)
    exe = engine.find_ffmpeg()
    wav = SAMPLES / "source_44k.wav"
    wav2 = SAMPLES / "source_48k_mono.wav"
    if not wav.exists():
        run([exe, "-hide_banner", "-y", "-f", "lavfi", "-i",
             "sine=frequency=440:duration=3:sample_rate=44100",
             "-af", "aeval=val(0)|val(0)*0.7", "-ac", "2", "-c:a", "pcm_s16le", str(wav)])
    if not wav2.exists():
        run([exe, "-hide_banner", "-y", "-f", "lavfi", "-i",
             "sine=frequency=330:duration=2:sample_rate=48000",
             "-ac", "1", "-c:a", "pcm_s24le", str(wav2)])
    return wav, wav2


def make_tagged_mp3() -> Path:
    """带 ID3 标签 + 内嵌封面的 mp3，用来验证元数据/封面复制。"""
    exe = engine.find_ffmpeg()
    tagged = SAMPLES / "tagged.mp3"
    cover = SAMPLES / "cover.png"
    if not cover.exists():
        run([exe, "-hide_banner", "-y", "-f", "lavfi", "-i",
             "color=c=0x4c8dff:s=320x320:d=1", "-frames:v", "1", str(cover)])
    if not tagged.exists():
        run([
            exe, "-hide_banner", "-y", "-i", str(SAMPLES / "source_44k.wav"),
            "-i", str(cover),
            "-map", "0:a", "-map", "1:v", "-c:v", "mjpeg", "-disposition:v", "attached_pic",
            "-c:a", "libmp3lame", "-b:a", "192k",
            "-metadata", "title=测试曲目", "-metadata", "artist=TransMusic",
            "-metadata", "album=自检专辑",
            "-id3v2_version", "3", str(tagged),
        ])
    return tagged


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    wav, wav_mono24 = make_sources()
    tagged = make_tagged_mp3()
    exe = engine.find_ffmpeg()
    print(f"引擎：{engine.ffmpeg_version().split(' Copyright')[0]}")
    print(f"样例：{wav.name} / {wav_mono24.name} / {tagged.name}\n")

    failures: list[str] = []
    rows: list[str] = []

    for key in FORMAT_ORDER:
        fmt = FORMATS[key]
        settings = ConvertSettings(
            format_key=key,
            bitrate=fmt.default_bitrate,
            vbr_index=0 if fmt.vbr_choices else -1,
            bit_depth=fmt.default_bit_depth,
            comp_level=fmt.default_comp() if fmt.comp_levels else -1,
        )
        src = wav if fmt.max_channels != 1 else wav_mono24
        info = engine.probe(src)
        dst = OUT / f"conv_{key}.{fmt.ext}"
        result = engine.convert(src, dst, fmt, settings, info)
        if not result.ok:
            failures.append(f"{key}: {result.message}")
            rows.append(f"{key:<9} 失败  {result.message[:80]}")
            continue
        got = engine.probe(dst)
        problems = []
        if got.duration <= 0:
            problems.append("时长异常")
        if abs(got.duration - info.duration) > max(0.3, info.duration * 0.05):
            problems.append(f"时长 {got.duration:.2f}s≠{info.duration:.2f}s")
        if got.sample_rate <= 0:
            problems.append("采样率缺失")
        if fmt.sample_rates and got.sample_rate not in fmt.sample_rates:
            problems.append(f"采样率 {got.sample_rate} 不在允许集合")
        if fmt.lossless and fmt.bit_depths and got.bit_depth and got.bit_depth not in (16, 24, 32):
            pass
        size_kb = dst.stat().st_size / 1024
        status = "OK" if not problems else "问题:" + "/".join(problems)
        if problems:
            failures.append(f"{key}: {problems}")
        rows.append(
            f"{key:<9} {status:<22} {got.codec:<12} {got.sample_rate:>6}Hz "
            f"{got.channels_text:<6} {got.bit_depth or '-':>3}bit {size_kb:>8.1f}KB  {got.duration:>5.2f}s"
        )

    print("── 目标格式矩阵（源：44.1k 立体声 / 单声道 24bit）")
    for r in rows:
        print("  " + r)

    # 二次转换链：flac → mp3 → ogg → opus → wav，检查互转链路
    print("\n── 互转链路 flac→mp3→ogg→opus→wav")
    chain = [
        ("flac", OUT / "conv_flac.flac"),
        ("mp3", None),
        ("ogg", None),
        ("opus", None),
        ("wav", None),
    ]
    prev = OUT / "conv_flac.flac"
    for key, _ in chain[1:]:
        fmt = FORMATS[key]
        settings = ConvertSettings(
            format_key=key, bitrate=fmt.default_bitrate, vbr_index=-1,
            bit_depth=fmt.default_bit_depth, comp_level=fmt.default_comp() if fmt.comp_levels else -1,
        )
        info = engine.probe(prev)
        dst = OUT / f"chain_{key}.{fmt.ext}"
        res = engine.convert(prev, dst, fmt, settings, info)
        if not res.ok:
            failures.append(f"chain {key}: {res.message}")
            print(f"  {key:<7} 失败：{res.message[:90]}")
            break
        got = engine.probe(dst)
        print(f"  {key:<7} OK  {got.codec:<10} {got.sr_text:<9} {got.channels_text:<6} {got.duration:.2f}s")
        prev = dst

    # 元数据 + 封面
    print("\n── 标签与封面复制（tagged.mp3 → mp3/m4a/flac）")
    tag_src = engine.probe(tagged)
    print(f"  源：封面={tag_src.has_cover} 标题={tag_src.title!r} 艺人={tag_src.artist!r}")
    for key in ("mp3", "m4a_aac", "flac"):
        fmt = FORMATS[key]
        settings = ConvertSettings(format_key=key, bitrate=fmt.default_bitrate,
                                   bit_depth=fmt.default_bit_depth, comp_level=-1)
        dst = OUT / f"tagged_{key}.{fmt.ext}"
        res = engine.convert(tagged, dst, fmt, settings, tag_src)
        if not res.ok:
            failures.append(f"tag {key}: {res.message}")
            print(f"  {key:<8} 失败：{res.message[:90]}")
            continue
        got = engine.probe(dst)
        flag = "封面OK" if got.has_cover else "封面丢失"
        print(f"  {key:<8} {flag:<8} 标题={got.title!r} 艺人={got.artist!r}")
        if not got.has_cover:
            failures.append(f"tag {key}: 封面未复制")
        if got.title != "测试曲目":
            failures.append(f"tag {key}: 标题未保留（{got.title!r}）")

    # 预设全部跑一遍
    print("\n── 预设参数")
    for preset in PRESETS:
        fmt = FORMATS[preset.format_key]
        settings = ConvertSettings(
            format_key=fmt.key,
            bitrate=preset.bitrate or fmt.default_bitrate,
            vbr_index=preset.vbr_index,
            sample_rate=preset.sample_rate,
            channels=preset.channels,
            bit_depth=preset.bit_depth or fmt.default_bit_depth,
            comp_level=preset.comp_level if preset.comp_level >= 0 else fmt.default_comp(),
        )
        info = engine.probe(wav)
        dst = OUT / f"preset_{preset.key}.{fmt.ext}"
        res = engine.convert(wav, dst, fmt, settings, info)
        if not res.ok:
            failures.append(f"preset {preset.key}: {res.message}")
            print(f"  {preset.label:<16} 失败：{res.message[:90]}")
            continue
        got = engine.probe(dst)
        print(f"  {preset.label:<16} OK  {got.codec:<10} {got.bitrate_text:<10} "
              f"{got.sr_text:<9} {got.channels_text}")

    # 取消
    print("\n── 取消机制")
    import threading

    long_src = OUT / "long_src.wav"
    if not long_src.exists():
        # 44100Hz 立体声 300 秒：让 FLAC 最高压缩级别的编码耗时足够长，取消才可复现
        run([exe, "-hide_banner", "-y", "-f", "lavfi", "-i",
             "sine=frequency=220:duration=300:sample_rate=44100", "-ac", "2",
             "-c:a", "pcm_s16le", str(long_src)])
    cancel = threading.Event()
    threading.Timer(0.9, cancel.set).start()
    fmt = FORMATS["flac"]
    res = engine.convert(long_src, OUT / "cancel_test.flac", fmt,
                         ConvertSettings(format_key="flac", bit_depth=16, comp_level=12),
                         engine.probe(long_src), cancel=cancel)
    print(f"  取消结果：ok={res.ok} canceled={res.canceled} 输出存在={(OUT / 'cancel_test.flac').exists()}")
    if res.ok or not res.canceled:
        failures.append("取消机制未生效")

    # 进度回调
    print("\n── 进度回调")
    seen: list[float] = []
    fmt = FORMATS["mp3"]
    res = engine.convert(wav, OUT / "progress_test.mp3", fmt,
                         ConvertSettings(format_key="mp3", bitrate=320),
                         engine.probe(wav), on_progress=lambda p, e, n: seen.append(p))
    print(f"  回调次数={len(seen)} 最大进度={max(seen) if seen else 0:.1f}%")

    print("\n" + "=" * 62)
    if failures:
        print(f"发现 {len(failures)} 个问题：")
        for f in failures:
            print("  ✘", f)
        return 1
    print("全部通过 ✔")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
