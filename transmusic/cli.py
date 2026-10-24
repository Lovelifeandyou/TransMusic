"""命令行批量模式：python main.py --cli -f flac -o out in1.mp3 in2.wav"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import __app_name__, __version__, engine
from .engine import AUDIO_EXTS, ConvertSettings, MediaInfo
from .formats import FORMAT_ORDER, FORMATS, get_format


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog=f"{__app_name__.lower()} --cli",
        description="音频格式批量转换（命令行模式）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python main.py --cli -f flac song.mp3 album/*.wav\n"
               "  python main.py --cli -f m4a_aac -b 256 -o D:\\out music\\\n",
    )
    ap.add_argument("inputs", nargs="*", help="音频文件或文件夹（文件夹会递归扫描）")
    ap.add_argument("-f", "--format", default="mp3", help=f"目标格式：{', '.join(FORMAT_ORDER)}")
    ap.add_argument("-o", "--output", default=None, help="输出目录（默认与源文件同目录）")
    ap.add_argument("-b", "--bitrate", type=int, default=0, help="码率 kbps（失真格式）")
    ap.add_argument("--vbr", type=int, default=-1, help="VBR 档位索引（见 --list-formats）")
    ap.add_argument("--sr", type=int, default=0, help="采样率 Hz，0 = 保持")
    ap.add_argument("--ch", type=int, default=0, help="声道数 1/2，0 = 保持")
    ap.add_argument("--bit-depth", type=int, default=0, help="位深 16/24/32（无损格式）")
    ap.add_argument("--compression", type=int, default=-1, help="无损压缩级别")
    ap.add_argument("-j", "--jobs", type=int, default=2, help="并发文件数")
    ap.add_argument("--template", default="{name}", help="文件名模板，如 '{artist} - {title}'")
    ap.add_argument("--conflict", choices=("rename", "overwrite", "skip"), default="rename")
    ap.add_argument("--no-metadata", action="store_true", help="不复制标签")
    ap.add_argument("--no-cover", action="store_true", help="不复制封面")
    ap.add_argument("-n", "--dry-run", action="store_true", help="只打印命令，不执行")
    ap.add_argument("--list-formats", action="store_true", help="列出支持的格式")
    ap.add_argument("-v", "--version", action="version", version=f"{__app_name__} {__version__}")
    return ap


def collect(inputs: list[str]) -> list[Path]:
    files: list[Path] = []
    for item in inputs:
        p = Path(item)
        if p.is_dir():
            files.extend(sorted(f for f in p.rglob("*") if f.is_file() and f.suffix.lower() in AUDIO_EXTS))
        elif p.is_file():
            files.append(p)
        else:
            print(f"[跳过] 不存在：{p}", file=sys.stderr)
    return files


def list_formats() -> None:
    print(f"{'键名':<10}{'扩展名':<8}{'解码器':<16}{'类型':<8}说明")
    print("-" * 96)
    for key in FORMAT_ORDER:
        f = FORMATS[key]
        kind = "无损" if f.lossless else "失真"
        extra = []
        if f.bitrates:
            extra.append("码率 " + "/".join(str(b) for b in f.bitrates[:5]) + "…")
        if f.vbr_choices:
            extra.append("VBR 档位 " + str(len(f.vbr_choices)))
        if f.bit_depths:
            extra.append("位深 " + "/".join(str(b) for b in f.bit_depths))
        if f.comp_levels:
            extra.append(f"压缩级别 {f.comp_levels[0]}-{f.comp_levels[1]}")
        print(f"{key:<10}{'.' + f.ext:<8}{f.codec:<16}{kind:<8}{'，'.join(extra)}  {f.note}")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.list_formats:
        list_formats()
        return 0
    if args.format not in FORMATS:
        print(f"未知格式 {args.format}，可用：{', '.join(FORMAT_ORDER)}", file=sys.stderr)
        return 2
    if not args.inputs:
        build_parser().print_help()
        return 2

    fmt = get_format(args.format)
    settings = ConvertSettings(
        format_key=fmt.key,
        bitrate=args.bitrate or fmt.default_bitrate,
        vbr_index=args.vbr,
        sample_rate=args.sr,
        channels=args.ch,
        bit_depth=args.bit_depth or fmt.default_bit_depth,
        comp_level=args.compression if args.compression >= 0 else fmt.default_comp(),
        keep_metadata=not args.no_metadata,
        keep_cover=not args.no_cover,
    )

    files = collect(args.inputs)
    if not files:
        print("没有找到可转换的音频文件", file=sys.stderr)
        return 1

    out_dir = Path(args.output) if args.output else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)

    try:
        exe = engine.find_ffmpeg()
    except engine.EngineError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 3
    print(f"{__app_name__} {__version__} · {engine.ffmpeg_version().split(' Copyright')[0]}")
    print(f"目标格式：{fmt.label}　共 {len(files)} 个文件\n")

    ok = fail = skip = 0
    started = time.time()
    for idx, src in enumerate(files, start=1):
        info = engine.probe(src)
        if not info.ok:
            print(f"[{idx}/{len(files)}] ✘ {src.name}：{info.error}")
            fail += 1
            continue
        dst = engine.resolve_output(src, out_dir, fmt, info, args.template, args.conflict, index=idx)
        if dst is None:
            print(f"[{idx}/{len(files)}] – {src.name}：目标已存在，跳过")
            skip += 1
            continue
        if args.dry_run:
            cmd = engine.build_command(exe, src, dst, fmt, settings, info)
            print(" ".join(f'"{c}"' if " " in c else c for c in cmd))
            continue

        bar_state = {"last": -1}

        def progress(pct: float, eta: float, note: str) -> None:
            step = int(pct // 5)
            if step != bar_state["last"]:
                bar_state["last"] = step
                print(f"\r[{idx}/{len(files)}] {src.name[:40]:<40} {pct:5.1f}% {note:<6}", end="", flush=True)

        result = engine.convert(src, dst, fmt, settings, info, on_progress=progress)
        print("\r", end="")
        if result.ok:
            size = dst.stat().st_size / 1024 / 1024
            print(f"[{idx}/{len(files)}] ✔ {src.name} → {dst.name}　{size:.2f} MB　{result.elapsed:.1f}s")
            ok += 1
        else:
            print(f"[{idx}/{len(files)}] ✘ {src.name}：{result.message}")
            fail += 1

    if not args.dry_run:
        print(f"\n完成：成功 {ok}，失败 {fail}，跳过 {skip}，用时 {time.time() - started:.1f}s")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
