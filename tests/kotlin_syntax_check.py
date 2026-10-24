"""Kotlin 语法级编译检查（无 Android SDK / Compose classpath，只验证语法）。

思路：用 kotlinc 编译 Android 源文件，把所有错误按类型分类：
  · 语法错误（expecting / unexpected tokens / unresolved syntax …）→ 真问题，失败
  · 语义错误（unresolved reference、cannot access class 等）→ 因为没有 android.jar 与
    Compose 依赖，属于预期，忽略

这样即使拿不到 SDK，也能拦住「括号/尾逗号/字符串/关键字写错」这类最致命的问题。

用法：python tests/kotlin_syntax_check.py
（需要 Kotlin 编译器：解压 kotlin-compiler 到 tools/kotlinc，或设置 KOTLINC 环境变量）
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN_DIR = ROOT / "android" / "app" / "src" / "main" / "java"

# 语法类错误的关键词（Kotlin 编译器原文）
SYNTAX_MARKERS = (
    "expecting",
    "unexpected tokens",
    "unexpected symbol",
    "Expecting",
    "Unexpected",
    "missing '}'",
    "missing ')'",
    "unbalanced",
    "syntax error",
)

ERROR_LINE = re.compile(r"^(.*\.kt):(\d+):(\d+): error: (.*)$")


def find_kotlinc() -> str | None:
    env = os.environ.get("KOTLINC")
    if env and Path(env).exists():
        return env
    for candidate in (
        ROOT / "tools" / "kotlinc" / "kotlinc" / "bin" / "kotlinc.bat",
        Path(tempfile.gettempdir()) / "tm-kotlinc" / "kotlinc" / "bin" / "kotlinc.bat",
    ):
        if candidate.exists():
            return str(candidate)
    return None


def main() -> int:
    kotlinc = find_kotlinc()
    if not kotlinc:
        print("跳过：未找到 Kotlin 编译器（可解压 kotlin-compiler 到 tools/kotlinc，或设 KOTLINC 环境变量）")
        return 0

    files = sorted(KOTLIN_DIR.rglob("*.kt"))
    if not files:
        print("没有找到 Kotlin 源文件")
        return 1

    out_dir = Path(tempfile.mkdtemp(prefix="tm-kt-"))
    cmd = [kotlinc, "-nowarn", "-d", str(out_dir)] + [str(f) for f in files]
    print(f"编译 {len(files)} 个 Kotlin 文件（无 Android classpath，只检查语法）…")
    result = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=600)
    output = (result.stdout or "") + (result.stderr or "")

    syntax_errors: list[str] = []
    semantic = 0
    for line in output.splitlines():
        m = ERROR_LINE.match(line.strip())
        if not m:
            continue
        message = m.group(4)
        if any(marker in message for marker in SYNTAX_MARKERS):
            syntax_errors.append(f"{Path(m.group(1)).name}:{m.group(2)}:{m.group(3)} {message}")
        else:
            semantic += 1

    print(f"语义类报错（缺 classpath，预期内）：{semantic} 条")
    print("=" * 62)
    if syntax_errors:
        print(f"发现 {len(syntax_errors)} 个语法错误：")
        for e in syntax_errors[:20]:
            print("  ✘", e)
        return 1
    print("Kotlin 语法检查通过 ✔（无括号/结构/关键字错误）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
