"""TransMusic 命令行入口（Windows 图形界面请运行 windows\\publish\\TransMusic.exe）。

    python main.py --cli -f flac -o D:\\out music\\
    python main.py --list-formats
    python main.py --help

这个入口只保留命令行能力：桌面端已由 WinUI 3 应用（windows/）承担，
Python 侧作为 CLI 批量工具 + 三端格式参数的单一数据源 + 自动化测试基准。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def main() -> int:
    from transmusic.cli import main as cli_main

    argv = sys.argv[1:]
    # 兼容旧写法：--cli 只是显式声明，其余参数原样传给 cli
    rest = [a for a in argv if a not in ("--cli", "-c")]
    if not rest:
        print(__doc__)
        cli_main(["--help"])
        return 0
    return cli_main(rest)


if __name__ == "__main__":
    raise SystemExit(main())
