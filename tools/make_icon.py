"""生成应用图标 assets/app.ico（纯标准库，无需 Pillow）。

图形：深蓝渐变圆角方块 + 中央声波柱。
用法：python tools/make_icon.py
"""
from __future__ import annotations

import math
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "assets" / "app.ico"
SS = 4  # 超采样倍数


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def hex2rgb(h: str) -> tuple[float, float, float]:
    h = h.lstrip("#")
    return (float(int(h[0:2], 16)), float(int(h[2:4], 16)), float(int(h[4:6], 16)))


def rounded_rect_cov(x: float, y: float, S: float, radius: float) -> float:
    cx = cy = S / 2.0
    dx = abs(x - cx) - (S / 2.0 - radius)
    dy = abs(y - cy) - (S / 2.0 - radius)
    d = math.hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0) - radius
    return max(0.0, min(1.0, 0.5 - d))


def capsule_cov(cx: float, cy: float, w: float, h: float, x: float, y: float) -> float:
    """竖直圆头柱的覆盖率（SDF）。"""
    r = w / 2.0
    seg = max(0.0, h / 2.0 - r)
    d = math.hypot(abs(x - cx), max(0.0, abs(y - cy) - seg)) - r
    return max(0.0, min(1.0, 0.5 - d))


def render(S: int) -> bytes:
    """返回自上而下的 BGRA 像素。"""
    big = S * SS
    top = hex2rgb("#31457f")
    bottom = hex2rgb("#131a2c")
    radius = big * 0.22

    if S >= 32:
        heights = (0.34, 0.58, 0.82, 0.50, 0.68)
        bar_w, gap = 0.090 * big, 0.072 * big
    else:
        heights = (0.46, 0.80, 0.60)
        bar_w, gap = 0.135 * big, 0.105 * big
    total = len(heights) * bar_w + (len(heights) - 1) * gap
    start_x = (big - total) / 2.0 + bar_w / 2.0
    centers = [start_x + i * (bar_w + gap) for i in range(len(heights))]

    buf = bytearray(big * big * 4)
    for j in range(big):
        y = j + 0.5
        t = y / big
        for i in range(big):
            x = i + 0.5
            a = rounded_rect_cov(x, y, big, radius)
            if a <= 0.0:
                continue
            glow = 0.14 * max(0.0, 1.0 - math.hypot(x / big - 0.24, y / big - 0.12) * 1.7)
            base = [lerp(top[k], bottom[k], t) + glow * 255 for k in range(3)]

            for n, hh in enumerate(heights):
                cov = capsule_cov(centers[n], big * 0.5, bar_w, big * hh, x, y)
                if cov > 0.0:
                    # 柱子自下而上 亮蓝 → 近白
                    tt = 1.0 - min(1.0, max(0.0, (big * 0.5 + big * hh / 2 - y) / (big * hh)))
                    col = (lerp(96, 214, tt), lerp(158, 232, tt), lerp(252, 255, tt))
                    for k in range(3):
                        base[k] = lerp(base[k], col[k], cov)
            o = (j * big + i) * 4
            buf[o + 0] = int(max(0, min(255, base[2])))  # B
            buf[o + 1] = int(max(0, min(255, base[1])))  # G
            buf[o + 2] = int(max(0, min(255, base[0])))  # R
            buf[o + 3] = int(a * 255)

    # 盒式降采样（alpha 加权）
    out = bytearray(S * S * 4)
    n = SS * SS
    for y in range(S):
        for x in range(S):
            r = g = b = a = 0
            for dy in range(SS):
                for dx in range(SS):
                    o = ((y * SS + dy) * big + (x * SS + dx)) * 4
                    al = buf[o + 3]
                    r += buf[o + 2] * al
                    g += buf[o + 1] * al
                    b += buf[o + 0] * al
                    a += al
            if a <= 0:
                continue
            o2 = (y * S + x) * 4
            out[o2 + 0] = int(b / a)
            out[o2 + 1] = int(g / a)
            out[o2 + 2] = int(r / a)
            out[o2 + 3] = a // n
    return bytes(out)


def dib_entry(S: int, bgra_top_down: bytes) -> bytes:
    header = struct.pack("<IiiHHIIiiII", 40, S, S * 2, 1, 32, 0, S * S * 4, 0, 0, 0, 0)
    rows = [bgra_top_down[y * S * 4:(y + 1) * S * 4] for y in range(S)]
    xor = b"".join(reversed(rows))  # BMP 自下而上
    and_stride = ((S + 31) // 32) * 4
    return header + xor + b"\x00" * (and_stride * S)


def main() -> None:
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [dib_entry(s, render(s)) for s in sizes]
    offset = 6 + 16 * len(sizes)
    entries = b""
    for s, data in zip(sizes, images):
        entries += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    ico = struct.pack("<HHH", 0, 1, len(sizes)) + entries + b"".join(images)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(ico)
    print(f"已生成 {OUT}  ({len(ico) / 1024:.0f} KB, {len(sizes)} 个尺寸)")


if __name__ == "__main__":
    main()
