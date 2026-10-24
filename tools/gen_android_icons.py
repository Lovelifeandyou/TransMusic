"""从 assets/app.ico 生成 Android 各密度启动图标。

用法：python tools/gen_android_icons.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from transmusic import engine  # noqa: E402

RES = ROOT / "android" / "app" / "src" / "main" / "res"

# 密度 → 启动图标边长（48dp 基准）
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main() -> None:
    src = ROOT / "assets" / "app.ico"
    if not src.exists():
        raise SystemExit("缺少 assets/app.ico，请先运行 tools/make_icon.py")
    exe = engine.find_ffmpeg()
    for folder, size in DENSITIES.items():
        out_dir = RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        target = out_dir / "ic_launcher.png"
        result = subprocess.run(
            [exe, "-hide_banner", "-y", "-i", str(src),
             "-vf", f"scale={size}:{size}:flags=lanczos",
             "-frames:v", "1", str(target)],
            capture_output=True, text=True, errors="replace",
        )
        ok = target.exists() and target.stat().st_size > 0
        print(f"{folder:<18}{size:>4}px  {'OK' if ok else '失败'}")
        if not ok:
            print(result.stderr[-400:])


if __name__ == "__main__":
    main()
