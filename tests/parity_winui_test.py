"""WinUI 端（TransMusic.Core, C#）与 Python 引擎的 ffmpeg 参数一致性测试。

做法：
  1. 用 tests/parity_cases.py 生成统一用例（15 种格式 × 多组参数 × 4 种源特征）
  2. dotnet 编译并运行 windows/TransMusic.Tests 的 parity 模式，得到 C# 端的参数列表
  3. 用 Python 端 engine.build_command 生成基准
  4. 逐条 diff

依赖：.NET SDK（DOTNET 环境变量可指定 dotnet.exe 路径）。
用法：python tests/parity_winui_test.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tests.parity_cases import (  # noqa: E402
    build_cases,
    diff_report,
    python_args,
    winui_json,
)

WIN = ROOT / "windows"
TESTS_PROJ = WIN / "TransMusic.Tests" / "TransMusic.Tests.csproj"
OUT_DIR = ROOT / "tests" / "_winui"
CASES_JSON = OUT_DIR / "cases.json"
RESULT_JSON = OUT_DIR / "result.json"


def find_dotnet() -> str | None:
    env = os.environ.get("DOTNET")
    if env and Path(env).exists():
        return env
    found = shutil.which("dotnet")
    if found:
        return found
    # 便携版 SDK（本机测试用）：%TEMP%\dotnet-sdk\dotnet.exe
    for candidate in (
        Path(os.environ.get("TEMP", "/tmp")) / "dotnet-sdk" / "dotnet.exe",
        Path(r"C:\Program Files\dotnet\dotnet.exe"),
    ):
        if candidate.exists():
            return str(candidate)
    return None


def main() -> int:
    dotnet = find_dotnet()
    if not dotnet:
        print("跳过：未找到 .NET SDK（安装 .NET 8 SDK 或设置 DOTNET 环境变量后重试）")
        return 0
    if not TESTS_PROJ.exists():
        print("缺少 TransMusic.Tests 工程")
        return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cases = build_cases()
    CASES_JSON.write_text(winui_json(cases), "utf-8")

    print(f".NET：{dotnet}")
    build = subprocess.run(
        [dotnet, "build", str(TESTS_PROJ), "-c", "Release", "-v", "q", "-nologo"],
        capture_output=True, text=True, errors="replace",
    )
    if build.returncode != 0:
        print(build.stdout[-2500:])
        print(build.stderr[-2500:])
        return 1

    run = subprocess.run(
        [dotnet, "run", "--project", str(TESTS_PROJ), "-c", "Release", "--no-build", "--",
         "parity", str(CASES_JSON), str(RESULT_JSON)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if run.returncode != 0 or not RESULT_JSON.exists():
        print(run.stdout[-2000:])
        print(run.stderr[-2000:])
        print("C# 侧执行失败")
        return 1
    print(run.stdout.strip())

    actual = json.loads(RESULT_JSON.read_text("utf-8"))
    mismatches: list[str] = []
    checked = 0
    for case in cases:
        expected = python_args(case)
        got = actual.get(case["id"])
        if got is None:
            mismatches.append(f"{case['id']}: C# 未输出结果")
            continue
        checked += 1
        if got != expected:
            mismatches.append(diff_report(case["id"], expected, got))

    sample = next(c for c in cases if c["id"] == "flac-v0-cd")
    print("\n样例（flac / CD 源）：")
    print("  " + " ".join(python_args(sample)))
    print(f"\n对比 {checked} 个用例")
    print("=" * 62)
    if mismatches:
        print(f"发现 {len(mismatches)} 处不一致：")
        for m in mismatches[:10]:
            print("  ✘", m)
        return 1
    print("WinUI 端与 Python 端参数完全一致 ✔")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
