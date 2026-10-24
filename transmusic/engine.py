"""ffmpeg 引擎层：定位可执行文件、探测媒体信息、构建命令、执行单个转换。"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

from .formats import KEEP, AudioFormat

SUBPROC_FLAGS = 0
if os.name == "nt":  # 隐藏弹出黑框
    SUBPROC_FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)

AUDIO_EXTS = {
    ".mp3", ".wav", ".flac", ".m4a", ".mp4", ".aac", ".ogg", ".oga", ".opus",
    ".wma", ".aif", ".aiff", ".aifc", ".alac", ".ape", ".wv", ".tta", ".spx",
    ".ac3", ".eac3", ".dts", ".mp2", ".mp1", ".amr", ".au", ".caf", ".mka",
    ".wavpack", ".dsf", ".dff", ".m4b", ".mpc", ".m3u", ".voc", ".snd",
    ".webm", ".mkv", ".mov", ".avi", ".flv", ".3gp", ".ts", ".m2ts",
}


class EngineError(RuntimeError):
    pass


# --------------------------------------------------------------------------- #
# ffmpeg 定位
# --------------------------------------------------------------------------- #
_ffmpeg_cache: Optional[str] = None


def _candidate_paths() -> list[Path]:
    here = Path(__file__).resolve().parent
    roots = [Path.cwd(), here, here.parent, here.parent.parent]
    # PyInstaller 打包后的临时解包目录
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        roots.insert(0, Path(meipass))
    exe_dir = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else None
    if exe_dir:
        roots.insert(0, exe_dir)
    names = ["ffmpeg.exe", "ffmpeg"]
    out: list[Path] = []
    for r in roots:
        for sub in ("", "bin", "tools", "tools/ffmpeg", "tools/ffmpeg/bin", "ffmpeg", "ffmpeg/bin"):
            for n in names:
                out.append(r / sub / n)
    return out


def find_ffmpeg(force: bool = False) -> str:
    """按优先级返回可用的 ffmpeg 路径；找不到抛 EngineError。"""
    global _ffmpeg_cache
    if _ffmpeg_cache and not force and Path(_ffmpeg_cache).exists():
        return _ffmpeg_cache

    env = os.environ.get("TRANSMUSIC_FFMPEG")
    if env and Path(env).exists():
        _ffmpeg_cache = env
        return env

    which = shutil.which("ffmpeg")
    if which:
        _ffmpeg_cache = which
        return which

    for p in _candidate_paths():
        if p.is_file():
            _ffmpeg_cache = str(p)
            return str(p)

    # pip 包 imageio-ffmpeg 自带的二进制
    try:
        import imageio_ffmpeg  # type: ignore

        exe = imageio_ffmpeg.get_ffmpeg_exe()
        if exe and Path(exe).exists():
            _ffmpeg_cache = exe
            return exe
    except Exception:
        pass

    raise EngineError(
        "未找到 ffmpeg。请把 ffmpeg.exe 放到程序目录（或 tools/ffmpeg/bin/），"
        "或执行：pip install imageio-ffmpeg"
    )


def ffmpeg_version() -> str:
    try:
        r = subprocess.run(
            [find_ffmpeg(), "-hide_banner", "-version"],
            capture_output=True, text=True, timeout=15, creationflags=SUBPROC_FLAGS,
        )
        return (r.stdout or "").splitlines()[0] if r.stdout else "unknown"
    except Exception as exc:  # noqa: BLE001
        return f"不可用（{exc}）"


# --------------------------------------------------------------------------- #
# 媒体信息探测
# --------------------------------------------------------------------------- #
@dataclass
class MediaInfo:
    path: str = ""
    duration: float = 0.0
    bitrate: int = 0
    sample_rate: int = 0
    channels: int = 0
    codec: str = ""
    container: str = ""
    bit_depth: int = 0
    has_cover: bool = False
    title: str = ""
    artist: str = ""
    album: str = ""
    ok: bool = True
    error: str = ""

    @property
    def duration_text(self) -> str:
        if self.duration <= 0:
            return "--:--"
        total = int(round(self.duration))
        h, rem = divmod(total, 3600)
        m, s = divmod(rem, 60)
        return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"

    @property
    def bitrate_text(self) -> str:
        if not self.bitrate:
            return "-"
        return f"{self.bitrate // 1000} kbps" if self.bitrate >= 1000 else f"{self.bitrate} bps"

    @property
    def sr_text(self) -> str:
        if not self.sample_rate:
            return "-"
        khz = self.sample_rate / 1000
        return f"{khz:g} kHz"

    @property
    def channels_text(self) -> str:
        return {1: "单声道", 2: "立体声"}.get(self.channels, f"{self.channels} 声道" if self.channels else "-")


_COVER_KEYS = ("covr", "metadata_block_picture", "APIC:", "discid", "WM/Picture")


def _mutagen_probe(path: Path) -> Optional[MediaInfo]:
    try:
        import mutagen  # type: ignore
    except Exception:
        return None
    try:
        m = mutagen.File(str(path))
    except Exception:
        return None
    if m is None or getattr(m, "info", None) is None:
        return None
    info = MediaInfo(path=str(path))
    inf = m.info
    info.duration = float(getattr(inf, "length", 0) or 0)
    br = int(getattr(inf, "bitrate", 0) or 0)
    info.bitrate = br
    info.sample_rate = int(getattr(inf, "sample_rate", 0) or 0)
    info.channels = int(getattr(inf, "channels", 0) or 0)
    info.bit_depth = int(getattr(inf, "bits_per_sample", 0) or 0)
    typename = type(m).__name__  # MP3 / FLAC / MP4 / OggVorbis ...
    codec_map = {
        "MP3": "mp3", "FLAC": "flac", "MP4": "aac/alac", "OggVorbis": "vorbis",
        "OggOpus": "opus", "OggSpeex": "speex", "WAVE": "pcm", "AIFF": "pcm",
        "ASF": "wma", "WavPack": "wavpack", "TrueAudio": "tta", "MonkeysAudio": "ape",
        "DSF": "dsd", "Musepack": "musepack",
    }
    info.codec = codec_map.get(typename, typename.lower())
    if typename == "MP4":  # 区分 AAC 与 ALAC
        raw = str(getattr(inf, "codec", "")).lower()
        info.codec = "alac" if "alac" in raw else "aac"
    info.container = path.suffix.lower().lstrip(".")
    # Opus 固定 48kHz，mutagen 不返回该字段
    if info.sample_rate <= 0 and typename == "OggOpus":
        info.sample_rate = 48000
    # FLAC / Ogg 的封面存在 .pictures 里，不在 tags 中
    pictures = getattr(m, "pictures", None)
    if pictures:
        try:
            info.has_cover = len(pictures) > 0
        except Exception:
            info.has_cover = True
    tags = m.tags
    if tags is not None:
        try:
            keys = list(tags.keys())
        except Exception:
            keys = []
        info.has_cover = info.has_cover or any(str(k).startswith(_COVER_KEYS) for k in keys)

        def pick(*names: str) -> str:
            for n in names:
                try:
                    v = tags.get(n)
                except Exception:
                    v = None
                if v:
                    if isinstance(v, list):
                        v = v[0]
                    return str(v)
            return ""

        info.title = pick("TIT2", "title", "\xa9nam")
        info.artist = pick("TPE1", "artist", "\xa9ART")
        info.album = pick("TALB", "album", "\xa9alb")
    return info


_DUR_RE = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")
_AUD_RE = re.compile(
    r"Stream #\d+:\d+.*?Audio:\s*([A-Za-z0-9_\-]+).*?(\d+)\s*Hz,\s*([^,]+)(?:,\s*([a-z0-9]+))?"
)
_BR_RE = re.compile(r"bitrate:\s*(\d+)\s*kb/s")
_COVER_RE = re.compile(r"Video:\s*(?:mjpeg|png|bmp|gif)", re.I)


def _ffmpeg_probe(path: Path) -> MediaInfo:
    info = MediaInfo(path=str(path), container=path.suffix.lower().lstrip("."))
    try:
        exe = find_ffmpeg()
        r = subprocess.run(
            [exe, "-hide_banner", "-i", str(path)],
            capture_output=True, text=True, timeout=30,
            creationflags=SUBPROC_FLAGS, errors="replace",
        )
        text = (r.stderr or "") + (r.stdout or "")
    except Exception as exc:  # noqa: BLE001
        info.ok = False
        info.error = str(exc)
        return info

    m = _DUR_RE.search(text)
    if m:
        info.duration = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    m = _AUD_RE.search(text)
    if m:
        info.codec = m.group(1).lower()
        info.sample_rate = int(m.group(2))
        ch = m.group(3).strip().lower()
        info.channels = {"mono": 1, "stereo": 2}.get(ch, 0)
        if not info.channels:
            nums = re.findall(r"(\d+)\s*channels?", text)
            info.channels = int(nums[0]) if nums else 0
        if m.group(4) and m.group(4).startswith("s") and m.group(4)[1:].isdigit():
            info.bit_depth = int(m.group(4)[1:])
    m = _BR_RE.search(text)
    if m:
        info.bitrate = int(m.group(1)) * 1000
    info.has_cover = bool(_COVER_RE.search(text))
    if not _AUD_RE.search(text):
        info.ok = False
        info.error = "没有音频流"
    return info


def probe(path: Path) -> MediaInfo:
    """先试 mutagen（快），字段缺失时用 ffmpeg 补齐，失败则完全走 ffmpeg。"""
    path = Path(path)
    info = _mutagen_probe(path)
    if info is not None and (info.duration > 0 or info.sample_rate > 0):
        if info.sample_rate <= 0 or info.channels <= 0:
            alt = _ffmpeg_probe(path)
            info.sample_rate = info.sample_rate or alt.sample_rate
            info.channels = info.channels or alt.channels
            info.has_cover = info.has_cover or alt.has_cover
            if not info.codec:
                info.codec = alt.codec
        if info.bitrate <= 0:
            try:
                info.bitrate = int(path.stat().st_size * 8 / info.duration) if info.duration else 0
            except Exception:
                pass
        return info
    alt = _ffmpeg_probe(path)
    # 少数容器 ffmpeg 报的编码器名更准
    ext_codec = {".mp2": "mp2", ".eac3": "eac3", ".spx": "speex", ".wv": "wavpack", ".tta": "tta"}
    fixed = ext_codec.get(path.suffix.lower())
    if fixed:
        alt.codec = fixed
    return alt


# --------------------------------------------------------------------------- #
# 转换设置与命令构建
# --------------------------------------------------------------------------- #
@dataclass
class ConvertSettings:
    format_key: str = "mp3"
    bitrate: int = 320          # kbps，CBR 模式
    vbr_index: int = -1         # -1 = CBR；>=0 = 用该 vbr 档位
    sample_rate: int = KEEP     # 0 = 保持
    channels: int = KEEP        # 0 = 保持
    bit_depth: int = 0          # 无损格式位深
    comp_level: int = -1        # 无损压缩级别
    keep_metadata: bool = True
    keep_cover: bool = True

    def copy(self) -> "ConvertSettings":
        return ConvertSettings(**self.__dict__)


def _nearest(values: tuple[int, ...], target: int) -> int:
    if not values:
        return target
    return min(values, key=lambda v: (abs(v - target), v))


def resolve_sample_rate(fmt: AudioFormat, request: int, src_sr: int) -> int:
    """决定 -ar 的取值；返回 0 表示不指定（沿用源）。"""
    if request:
        if fmt.sample_rates and request not in fmt.sample_rates:
            return _nearest(fmt.sample_rates, request)
        return request
    if src_sr and fmt.sample_rates and src_sr not in fmt.sample_rates:
        return _nearest(fmt.sample_rates, src_sr)
    return 0


def resolve_channels(fmt: AudioFormat, request: int, src_ch: int) -> int:
    if fmt.max_channels and request and request > fmt.max_channels:
        return fmt.max_channels
    if fmt.max_channels == 1:
        return 1
    return request


def resolve_bit_depth(fmt: AudioFormat, request: int) -> int:
    if fmt.sample_fmts:
        if request and request in fmt.sample_fmts:
            return request
        return fmt.default_bit_depth or next(iter(fmt.sample_fmts))
    return 0


def build_command(
    ffmpeg: str,
    src: Path,
    dst: Path,
    fmt: AudioFormat,
    settings: ConvertSettings,
    info: Optional[MediaInfo] = None,
    report_progress: bool = True,
) -> list[str]:
    info = info or MediaInfo()
    cmd: list[str] = [
        ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
        "-i", str(src),
    ]

    copy_cover = bool(fmt.cover and settings.keep_cover and info.has_cover)
    cmd += ["-map", "0:a:0"]
    if copy_cover:
        cmd += ["-map", "0:v:0?", "-c:v", "copy", "-disposition:v", "attached_pic"]
    else:
        cmd += ["-vn"]
    cmd += ["-sn", "-dn"]

    # ---- 编码器与质量 ----
    depth = resolve_bit_depth(fmt, settings.bit_depth) if fmt.lossless else 0
    codec = fmt.codec
    if fmt.lossless and fmt.sample_fmts and depth in fmt.sample_fmts:
        mapped = fmt.sample_fmts[depth]
        if mapped.startswith("pcm_"):
            codec = mapped            # pcm 系列直接由编码器名决定位深
        else:
            codec = fmt.codec
    cmd += ["-c:a", codec]

    if fmt.lossless and fmt.sample_fmts and depth and not codec.startswith("pcm_"):
        cmd += ["-sample_fmt", fmt.sample_fmts[depth]]

    if fmt.lossless:
        if fmt.comp_levels:
            level = settings.comp_level if settings.comp_level >= 0 else fmt.default_comp()
            level = min(max(level, fmt.comp_levels[0]), fmt.comp_levels[1])
            cmd += ["-compression_level", str(level)]
    else:
        if settings.vbr_index >= 0 and fmt.vbr_choices:
            q = fmt.vbr_choices[min(settings.vbr_index, len(fmt.vbr_choices) - 1)][1]
            if fmt.key in ("opus",):
                cmd += ["-b:a", f"{q}k", "-vbr", "on"]
            elif fmt.key == "ogg":
                cmd += ["-q:a", str(q)]
            else:
                cmd += ["-q:a", str(q)]
        else:
            br = settings.bitrate or fmt.default_bitrate
            cmd += ["-b:a", f"{br}k"]

    # ---- opus 编码器复杂度 ----
    if fmt.key == "opus":
        cmd += ["-compression_level", "10", "-application", "audio"]

    # ---- 采样率 / 声道 ----
    sr = resolve_sample_rate(fmt, settings.sample_rate, info.sample_rate)
    if sr:
        cmd += ["-ar", str(sr)]
    ch = resolve_channels(fmt, settings.channels, info.channels)
    if ch:
        cmd += ["-ac", str(ch)]

    # ---- 元数据 / 封面 ----
    if settings.keep_metadata and fmt.tags:
        cmd += ["-map_metadata", "0"]
    else:
        cmd += ["-map_metadata", "-1"]
    cmd += ["-map_chapters", "-1"]
    if fmt.key == "mp3":
        cmd += ["-id3v2_version", "3", "-write_id3v1", "1"]
    if fmt.muxer == "ipod":
        cmd += ["-movflags", "+faststart"]

    # -progress/pipe 仅桌面端需要（Android 用 FFmpegKit 的 statistics 回调）
    if report_progress:
        cmd += ["-progress", "pipe:1", "-nostats"]
    cmd += [str(dst)]
    return cmd


# --------------------------------------------------------------------------- #
# 输出路径解析
# --------------------------------------------------------------------------- #
_BAD_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def sanitize(name: str) -> str:
    name = _BAD_CHARS.sub("_", name).strip(" .")
    return name[:150] or "output"


_CONFLICT_MODES = ("rename", "overwrite", "skip")


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem, suffix, parent = path.stem, path.suffix, path.parent
    for i in range(1, 10000):
        cand = parent / f"{stem} ({i}){suffix}"
        if not cand.exists():
            return cand
    return parent / f"{stem} ({int(time.time())}){suffix}"


def render_template(template: str, src: Path, fmt: AudioFormat, info: MediaInfo, index: int) -> str:
    """文件名模板：{name} {artist} {title} {album} {index} {bitrate} {ext}"""
    values = {
        "name": src.stem,
        "artist": info.artist or "",
        "title": info.title or src.stem,
        "album": info.album or "",
        "index": f"{index:02d}",
        "bitrate": str(info.bitrate // 1000 if info.bitrate else ""),
        "ext": fmt.ext,
    }
    text = template or "{name}"
    try:
        out = text.format(**values)
    except Exception:
        out = src.stem
    out = sanitize(out)
    return out + "." + fmt.ext


def resolve_output(
    src: Path,
    out_dir: Optional[Path],
    fmt: AudioFormat,
    info: MediaInfo,
    template: str = "{name}",
    conflict: str = "rename",
    index: int = 1,
) -> Optional[Path]:
    """返回目标路径；conflict='skip' 且已存在时返回 None。"""
    base = Path(out_dir) if out_dir else src.parent
    filename = render_template(template, src, fmt, info, index)
    dst = base / filename
    if dst.exists() and dst.resolve() == src.resolve():
        # 目标是源文件本身（同格式原地转换）时改名，避免自毁
        return unique_path(base / (sanitize(src.stem) + "_converted." + fmt.ext))
    if not dst.exists():
        return dst
    if conflict == "overwrite":
        return dst
    if conflict == "skip":
        return None
    return unique_path(dst)


# --------------------------------------------------------------------------- #
# 执行转换
# --------------------------------------------------------------------------- #
@dataclass
class ConvertResult:
    ok: bool
    dst: Optional[Path] = None
    message: str = ""
    elapsed: float = 0.0
    canceled: bool = False
    command: list[str] = field(default_factory=list)


ProgressCB = Callable[[float, float, str], None]  # (percent, eta_seconds, note)


def _parse_progress_block(block: dict[str, str], duration: float) -> tuple[float, float, str]:
    """解析 ffmpeg -progress 输出块，返回 (进度%, ETA秒, 速度说明)。"""
    pct, eta, note = 0.0, 0.0, ""
    t = block.get("out_time") or block.get("out_time_us", "")
    seconds = 0.0
    if ":" in str(t):
        try:
            h, m, s = str(t).split(":")
            seconds = int(h) * 3600 + int(m) * 60 + float(s)
        except Exception:
            seconds = 0.0
    elif str(t).isdigit():
        seconds = int(t) / 1_000_000
    speed = 0.0
    sp = block.get("speed", "").rstrip("x")
    try:
        speed = float(sp)
    except Exception:
        speed = 0.0
    if duration > 0 and seconds > 0:
        pct = min(99.5, seconds / duration * 100.0)
        if speed > 0:
            eta = max(0.0, (duration - seconds) / speed)
    if speed > 0:
        note = f"{speed:.1f}x"
    return pct, eta, note


def convert(
    src: Path,
    dst: Path,
    fmt: AudioFormat,
    settings: ConvertSettings,
    info: Optional[MediaInfo] = None,
    on_progress: Optional[ProgressCB] = None,
    cancel: Optional[threading.Event] = None,
) -> ConvertResult:
    """同步执行一次转换。on_progress 在转换线程内被调用。"""
    started = time.time()
    info = info or MediaInfo()
    if dst.exists():
        try:
            dst.unlink()
        except Exception:
            pass
    dst.parent.mkdir(parents=True, exist_ok=True)

    try:
        cmd = build_command(find_ffmpeg(), src, dst, fmt, settings, info)
    except EngineError as exc:
        return ConvertResult(False, None, str(exc), 0.0)

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.DEVNULL,
        creationflags=SUBPROC_FLAGS,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    err_chunks: list[str] = []

    def _drain() -> None:
        try:
            assert proc.stderr is not None
            for line in proc.stderr:
                err_chunks.append(line)
        except Exception:
            pass

    drain = threading.Thread(target=_drain, daemon=True)
    drain.start()

    block: dict[str, str] = {}
    canceled = False
    try:
        assert proc.stdout is not None
        for raw in proc.stdout:
            if cancel is not None and cancel.is_set():
                canceled = True
                break
            line = raw.strip()
            if not line:
                continue
            key, _, val = line.partition("=")
            block[key] = val
            if key == "progress":
                if on_progress and info.duration > 0:
                    pct, eta, note = _parse_progress_block(block, info.duration)
                    on_progress(pct, eta, note)
                block = {}
    finally:
        if canceled and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except Exception:
                proc.kill()
        code = proc.wait()
        drain.join(timeout=3)

    elapsed = time.time() - started
    if canceled:
        try:
            if dst.exists():
                dst.unlink()
        except Exception:
            pass
        return ConvertResult(False, None, "已取消", elapsed, True, cmd)

    if code != 0:
        err = "".join(err_chunks).strip()
        tail = "\n".join(err.splitlines()[-6:]) if err else f"ffmpeg 退出码 {code}"
        return ConvertResult(False, None, tail, elapsed, False, cmd)

    if not dst.exists() or dst.stat().st_size == 0:
        return ConvertResult(False, None, "输出文件为空", elapsed, False, cmd)

    if on_progress:
        on_progress(100.0, 0.0, "完成")
    return ConvertResult(True, dst, "完成", elapsed, False, cmd)
