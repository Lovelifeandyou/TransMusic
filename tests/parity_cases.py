"""三端参数一致性测试的公共用例生成器。

用例来源唯一（Python 的 formats.py），同时喂给：
  · tests/parity_test.py        —— Android 端 CommandBuilder.kt（kotlinc 编译后跑）
  · tests/parity_winui_test.py  —— Windows 端 CommandBuilder.cs（dotnet 编译后跑）
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from transmusic import engine  # noqa: E402
from transmusic.engine import ConvertSettings, MediaInfo  # noqa: E402
from transmusic.formats import FORMAT_ORDER, FORMATS  # noqa: E402

SRC_PATH = "D:/音乐/in 1.mp3"
DST_PATH = "D:/out/out 1.flac"

SOURCES = {
    "cd": {"srcSr": 44100, "srcCh": 2, "srcCover": False},
    "hi": {"srcSr": 96000, "srcCh": 2, "srcCover": True},
    "mono24": {"srcSr": 48000, "srcCh": 1, "srcCover": True},
    "odd": {"srcSr": 32000, "srcCh": 6, "srcCover": False},
}


def build_cases() -> list[dict]:
    cases: list[dict] = []
    for key in FORMAT_ORDER:
        fmt = FORMATS[key]
        variants: list[dict] = [
            {
                "bitrate": fmt.default_bitrate,
                "vbr": -1,
                "depth": fmt.default_bit_depth,
                "comp": fmt.default_comp() if fmt.comp_levels else -1,
            },
            {  # 全部保持原样 / 关闭元数据
                "sr": 0, "ch": 0, "meta": False, "cover": False,
                "bitrate": fmt.default_bitrate,
                "depth": fmt.default_bit_depth,
                "comp": -1,
            },
            {  # 强制 48kHz 单声道
                "sr": 48000, "ch": 1,
                "bitrate": fmt.default_bitrate,
                "depth": fmt.default_bit_depth,
                "comp": -1,
            },
        ]
        if fmt.vbr_choices:
            variants.append({"bitrate": 0, "vbr": 0, "depth": 0, "comp": -1})
        if fmt.comp_levels:
            variants.append({
                "bitrate": fmt.default_bitrate,
                "depth": fmt.bit_depths[-1] if fmt.bit_depths else 0,
                "comp": fmt.comp_levels[1],
            })
        if fmt.sample_rates:
            variants.append({
                "sr": fmt.sample_rates[0],
                "bitrate": fmt.default_bitrate,
                "depth": fmt.default_bit_depth,
                "comp": -1,
            })

        for vi, variant in enumerate(variants):
            for src_name, src in SOURCES.items():
                cases.append({
                    "id": f"{key}-v{vi}-{src_name}",
                    "fmt": key,
                    "bitrate": variant.get("bitrate", fmt.default_bitrate),
                    "vbr": variant.get("vbr", -1),
                    "sr": variant.get("sr", 0),
                    "ch": variant.get("ch", 0),
                    "depth": variant.get("depth", fmt.default_bit_depth),
                    "comp": variant.get("comp", -1),
                    "meta": variant.get("meta", True),
                    "cover": variant.get("cover", True),
                    **src,
                })
    return cases


def python_args(case: dict) -> list[str]:
    """用 Python（Windows 桌面版引擎）生成同一批参数，作为基准。"""
    fmt = FORMATS[case["fmt"]]
    settings = ConvertSettings(
        format_key=case["fmt"],
        bitrate=case["bitrate"],
        vbr_index=case["vbr"],
        sample_rate=case["sr"],
        channels=case["ch"],
        bit_depth=case["depth"],
        comp_level=case["comp"],
        keep_metadata=case["meta"],
        keep_cover=case["cover"],
    )
    info = MediaInfo(
        path=SRC_PATH,
        sample_rate=case["srcSr"],
        channels=case["srcCh"],
        has_cover=case["srcCover"],
    )
    cmd = engine.build_command(
        engine.find_ffmpeg(), SRC_PATH, DST_PATH, fmt, settings, info,
        report_progress=False,   # 各端进度上报通道不同，不参与对比
    )
    return cmd[1:]  # 去掉 ffmpeg 可执行文件本身


def kotlin_protocol(cases: list[dict]) -> str:
    lines: list[str] = []
    for c in cases:
        lines.append(f"CASE {c['id']}")
        lines.append(f"fmt={c['fmt']}")
        lines.append(f"bitrate={c['bitrate']}")
        lines.append(f"vbr={c['vbr']}")
        lines.append(f"sr={c['sr']}")
        lines.append(f"ch={c['ch']}")
        lines.append(f"depth={c['depth']}")
        lines.append(f"comp={c['comp']}")
        lines.append(f"meta={1 if c['meta'] else 0}")
        lines.append(f"cover={1 if c['cover'] else 0}")
        lines.append(f"srcSr={c['srcSr']}")
        lines.append(f"srcCh={c['srcCh']}")
        lines.append(f"srcCover={1 if c['srcCover'] else 0}")
        lines.append(f"srcPath={SRC_PATH}")
        lines.append(f"dstPath={DST_PATH}")
        lines.append("END")
    return "\n".join(lines) + "\n"


def winui_json(cases: list[dict]) -> str:
    payload = [
        {
            "id": c["id"],
            "fmt": c["fmt"],
            "bitrate": c["bitrate"],
            "vbr": c["vbr"],
            "sr": c["sr"],
            "ch": c["ch"],
            "depth": c["depth"],
            "comp": c["comp"],
            "metaValue": 1 if c["meta"] else 0,
            "coverValue": 1 if c["cover"] else 0,
            "srcSr": c["srcSr"],
            "srcCh": c["srcCh"],
            "srcCover": 1 if c["srcCover"] else 0,
        }
        for c in cases
    ]
    return json.dumps(payload, ensure_ascii=False, indent=1)


def diff_report(case_id: str, expected: list[str], actual: list[str]) -> str:
    lines = [f"{case_id}:"]
    for i in range(max(len(expected), len(actual))):
        e = expected[i] if i < len(expected) else "<缺失>"
        a = actual[i] if i < len(actual) else "<缺失>"
        if e != a:
            lines.append(f"    位置 {i}: python={e!r}  实现={a!r}")
    return "\n".join(lines[:9])
