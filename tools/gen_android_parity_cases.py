"""把三端一致性用例导出成 Android 单测资源。

产出 android/app/src/test/resources/parity_cases.txt，格式：

    CASE <id>
    <期望参数1>\x01<期望参数2>\x01...

第一行是 Python 引擎（唯一数据源）算出来的 ffmpeg 参数，第二行是构造该用例所需的字段。
Android 端单测 ParityTest.kt 会读这个文件，用 CommandBuilder 重新算一遍并逐参数比对。

用法：python tools/gen_android_parity_cases.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tests.parity_cases import SRC_PATH, DST_PATH, build_cases, python_args  # noqa: E402

SEP = "\x01"
OUT = ROOT / "android" / "app" / "src" / "test" / "resources" / "parity_cases.txt"


def main() -> int:
    cases = build_cases()
    lines: list[str] = [f"# src={SRC_PATH}", f"# dst={DST_PATH}", f"# total={len(cases)}"]
    for case in cases:
        expected = python_args(case)
        fields = SEP.join(
            [
                case["id"],
                case["fmt"],
                str(case["bitrate"]),
                str(case["vbr"]),
                str(case["sr"]),
                str(case["ch"]),
                str(case["depth"]),
                str(case["comp"]),
                "1" if case["meta"] else "0",
                "1" if case["cover"] else "0",
                str(case["srcSr"]),
                str(case["srcCh"]),
                "1" if case["srcCover"] else "0",
                case.get("srcCodec", ""),
            ]
        )
        lines.append(fields)
        lines.append(SEP.join(expected))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    size_kb = OUT.stat().st_size / 1024
    print(f"已生成 {OUT}")
    print(f"  {len(cases)} 个用例，{size_kb:.1f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
