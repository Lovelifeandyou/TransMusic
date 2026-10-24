"""生成一组演示音频（给截图/手工试用用），不进测试流程。

用法：python tools/make_demo_audio.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from transmusic import engine  # noqa: E402

DEMO = ROOT / "samples" / "demo"

SPECS = [
    ("01 夜航船.mp3", "sine=frequency=440:duration=64:sample_rate=44100", "libmp3lame", "192k"),
    ("02 无声之境.flac", "sine=frequency=523:duration=48:sample_rate=48000", "flac", None),
    ("03 雨落长安.m4a", "sine=frequency=349:duration=95:sample_rate=44100", "aac", "256k"),
    ("04 旧唱片.ogg", "sine=frequency=294:duration=132:sample_rate=44100", "libvorbis", None),
    ("05 山海之间.wma", "sine=frequency=392:duration=72:sample_rate=44100", "wmav2", "192k"),
    ("06 长夜将尽.wav", "sine=frequency=261:duration=18:sample_rate=44100", "pcm_s16le", None),
]


def main() -> None:
    DEMO.mkdir(parents=True, exist_ok=True)
    exe = engine.find_ffmpeg()
    for name, lavfi, codec, bitrate in SPECS:
        dst = DEMO / name
        if dst.exists():
            print(f"跳过 {name}")
            continue
        cmd = [exe, "-hide_banner", "-y", "-f", "lavfi", "-i", lavfi,
               "-af", "aeval=val(0)|val(0)*0.6", "-ac", "2", "-c:a", codec]
        if bitrate:
            cmd += ["-b:a", bitrate]
        if dst.suffix == ".m4a":
            cmd += ["-movflags", "+faststart"]
        cmd += ["-metadata", f"title={dst.stem}", "-metadata", "artist=夜航乐队",
                "-metadata", "album=演示专辑", str(dst)]
        subprocess.run(cmd, capture_output=True)
        size = dst.stat().st_size / 1024 if dst.exists() else 0
        print(f"{name:<20} {size:>8.0f} KB")


if __name__ == "__main__":
    main()
