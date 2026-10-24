"""把 ffmpeg 可执行文件放到 WinUI 工程目录（构建时随程序复制）。

用法：python tools/fetch_ffmpeg.py            # 用 imageio-ffmpeg 自带的二进制
      python tools/fetch_ffmpeg.py <path>     # 用指定的 ffmpeg.exe
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET_DIR = ROOT / "windows" / "TransMusic.App" / "ffmpeg"
TARGET = TARGET_DIR / "ffmpeg.exe"


def main() -> int:
    TARGET_DIR.mkdir(parents=True, exist_ok=True)

    if len(sys.argv) > 1:
        source = Path(sys.argv[1])
        if not source.exists():
            print(f"找不到 {source}")
            return 1
    else:
        try:
            import imageio_ffmpeg  # type: ignore

            source = Path(imageio_ffmpeg.get_ffmpeg_exe())
        except Exception:
            print("没有 imageio-ffmpeg，也没指定路径。")
            print("  pip install imageio-ffmpeg 后重试，或：python tools/fetch_ffmpeg.py D:\\ffmpeg\\bin\\ffmpeg.exe")
            return 1

    shutil.copyfile(source, TARGET)
    print(f"已复制 {source} → {TARGET}（{TARGET.stat().st_size / 1024 / 1024:.1f} MB）")
    print("现在可以直接构建/运行 WinUI 应用；ffmpeg 会被复制到输出目录的 ffmpeg\\ 子目录。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
