"""Android 端 Kotlin 引擎 ↔ Python 引擎 的参数一致性测试。

  1. 用 tests/parity_cases.py 生成统一用例
  2. 用 kotlinc 编译 Android 端真实的 CommandBuilder.kt（+ 生成的 Formats.kt），跑 Harness 输出参数
  3. 用 Python 端 engine.build_command 生成基准
  4. 逐条 diff

依赖：tools/kotlinc（Kotlin 编译器）+ JDK（改动 Kotlin 源码后才需要重编）。
用法：python tests/parity_test.py
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tests.parity_cases import (  # noqa: E402
    build_cases,
    diff_report,
    kotlin_protocol,
    python_args,
)

PARITY = ROOT / "tests" / "parity"
JAR = PARITY / "harness.jar"
KOTLINC = ROOT / "tools" / "kotlinc" / "kotlinc" / "bin" / "kotlinc.bat"
ANDROID_SRC = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "transmusic" / "converter"


def compile_harness(force: bool = False) -> None:
    sources = [
        ANDROID_SRC / "model" / "Formats.kt",
        ANDROID_SRC / "engine" / "CommandBuilder.kt",
        ANDROID_SRC / "engine" / "ConvertSettings.kt",
        PARITY / "MediaInfoStub.kt",
        PARITY / "Harness.kt",
    ]
    for s in sources:
        if not s.exists():
            raise SystemExit(f"缺少源文件：{s}")

    needs = force or not JAR.exists() or any(s.stat().st_mtime > JAR.stat().st_mtime for s in sources)
    if not needs:
        return  # 已有最新编译产物，直接复用
    if not KOTLINC.exists():
        raise SystemExit(
            f"缺少 Kotlin 编译器：{KOTLINC}\n"
            "  Android 端 354 用例一致性已由 Gradle 单测覆盖，无需 kotlinc：\n"
        "      cd android && gradle testDebugUnitTest\n"
        "  期望值来源：python tools/gen_android_parity_cases.py\n"
        "  仍想用本方案：下载 kotlin-compiler 解压到 tools/kotlinc"
        )
    print("编译 Android 端 CommandBuilder（kotlinc）…")
    cmd = [str(KOTLINC), "-nowarn", "-include-runtime", "-d", str(JAR)] + [str(s) for s in sources]
    result = subprocess.run(cmd, capture_output=True, text=True, errors="replace", cwd=str(PARITY))
    if not JAR.exists():
        print(result.stdout[-3000:])
        print(result.stderr[-3000:])
        raise SystemExit("Kotlin 编译失败")


def run_harness(protocol_path: Path) -> dict[str, list[str]]:
    result = subprocess.run(
        [
            "java",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-jar",
            str(JAR),
        ],
        stdin=protocol_path.open("r", encoding="utf-8"),
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        print(result.stderr[-3000:])
        raise SystemExit("Harness 运行失败")
    parsed: dict[str, list[str]] = {}
    current: str | None = None
    for line in result.stdout.splitlines():
        if line.startswith("CASE "):
            current = line[5:].strip()
            parsed[current] = []
        elif line.startswith("ARG ") and current:
            parsed[current].append(line[4:])
        elif line == "END":
            current = None
    return parsed


def main() -> int:
    cases = build_cases()
    (PARITY / "cases.txt").write_text(kotlin_protocol(cases), "utf-8")

    compile_harness()
    kotlin = run_harness(PARITY / "cases.txt")

    mismatches: list[str] = []
    checked = 0
    t0 = time.time()
    for case in cases:
        expected = python_args(case)
        actual = kotlin.get(case["id"])
        if actual is None:
            mismatches.append(f"{case['id']}: Kotlin 未输出结果")
            continue
        checked += 1
        if expected != actual:
            mismatches.append(diff_report(case["id"], expected, actual))

    sample = next(c for c in cases if c["id"] == "flac-v0-cd")
    print("样例（flac / CD 源）：")
    print("  " + " ".join(python_args(sample)))
    print(f"\n对比 {checked} 个用例（{time.time() - t0:.1f}s）")
    print("=" * 62)
    if mismatches:
        print(f"发现 {len(mismatches)} 处不一致：")
        for m in mismatches[:12]:
            print("  ✘", m)
        return 1
    print("Android 端与 Python 端参数完全一致 ✔")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
