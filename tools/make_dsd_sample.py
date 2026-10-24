"""生成一个真实的 DSF（DSD）测试文件，用于验证 DSD 解码链路。

ffmpeg 只有 DSD 解码器、没有 DSD 编码器，所以测试素材必须自己合成：
用一阶 sigma-delta 调制把正弦波变成 1bit 位流，再按 DSF 规范封装。

用法：python tools/make_dsd_sample.py [输出路径] [时长秒] [DSD 倍频]
      python tools/make_dsd_sample.py samples/dsd64.wav.dsf 1.0 64
"""
from __future__ import annotations

import math
import struct
import sys
from pathlib import Path

BASE_RATE = 2_822_400  # DSD64 = 44.1kHz * 64


def sigma_delta(freq: float, seconds: float, rate: int, amplitude: float = 0.5) -> bytes:
    """一阶 sigma-delta：输出 DSD 位流（每字节 8 个采样，LSB 先出）。"""
    total = int(rate * seconds)
    out = bytearray(total // 8)
    integrator = 0.0
    byte = 0
    bit_index = 0
    step = 2.0 * math.pi * freq / rate
    for i in range(total):
        target = amplitude * math.sin(step * i)
        integrator += target - (1.0 if integrator >= 0 else -1.0)
        if integrator >= 0:
            byte |= 1 << bit_index
        bit_index += 1
        if bit_index == 8:
            out[i >> 3] = byte
            byte = 0
            bit_index = 0
    return bytes(out)


def build_dsf(channels: list[bytes], rate: int, block_size: int = 4096) -> bytes:
    channel_count = len(channels)
    per_channel = len(channels[0])
    # 补齐到 block_size 的整数倍
    pad = (-per_channel) % block_size
    if pad:
        channels = [c + bytes(pad) for c in channels]
        per_channel += pad

    data = bytearray()
    blocks = per_channel // block_size
    for b in range(blocks):
        start = b * block_size
        for c in range(channel_count):
            data += channels[c][start:start + block_size]

    fmt_chunk = struct.pack(
        "<4sQIIIIIIQII",
        b"fmt ",
        52,
        1,                 # format version
        0,                 # format id: DSD raw
        2,                 # channel type: stereo
        channel_count,
        rate,
        1,                 # bits per sample
        per_channel * 8,   # sample count（按 1bit 计）
        block_size,
        0,                 # reserved
    )
    data_chunk_header = struct.pack("<4sQ", b"data", 12 + len(data))
    dsd_chunk_size = 28
    total = dsd_chunk_size + len(fmt_chunk) + len(data_chunk_header) + len(data)
    dsd_chunk = struct.pack("<4sQQQ", b"DSD ", dsd_chunk_size, total, 0)
    return dsd_chunk + fmt_chunk + data_chunk_header + bytes(data)


def main() -> int:
    args = sys.argv[1:]
    out = Path(args[0]) if args else Path("samples/dsd64_test.dsf")
    seconds = float(args[1]) if len(args) > 1 else 1.0
    mult = int(args[2]) if len(args) > 2 else 64
    rate = BASE_RATE * (mult // 64)

    print(f"生成 DSF：{out}  时长 {seconds}s  DSD{mult}  {rate} Hz  单声道位流 ×2")
    left = sigma_delta(1000.0, seconds, rate)
    right = sigma_delta(1500.0, seconds, rate)
    payload = build_dsf([left, right], rate)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(payload)
    print(f"完成：{out}  {len(payload) / 1024 / 1024:.2f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
