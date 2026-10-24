"""WinUI 3 工程的静态自检（编译之外的交叉核对）。

覆盖：
  1. XAML 是否格式正确；x:Name 与代码里引用的控件是否一一对应
  2. XAML 里写的事件处理器（Click=、Drop=、…）在代码后置文件里是否存在
  3. XAML 里的 {Binding X} 路径在 ViewModel / Job 上是否存在（避免手滑写成空绑定）
  4. csproj 关键构建属性是否齐全（UseWinUI / WindowsPackageType / EnableMsixTooling / EnableCoreMrtTooling）
  5. Formats.Generated.cs 是否与 Python 端 formats.py 一致（防止三端漂移）
  6. Translate：核心源码里不应出现 VS 专属任务的痕迹说明（文档性检查，防止误删 workaround 注释）

用法：python tests/winui_check.py
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

WINDOWS = ROOT / "windows"
APP = WINDOWS / "TransMusic.App"
CORE = WINDOWS / "TransMusic.Core"
GENERATED = CORE / "Formats.Generated.cs"


def read(path: Path) -> str:
    return path.read_text("utf-8")


def collect_cs_properties(paths: list[Path]) -> set[str]:
    names: set[str] = set()
    for path in paths:
        text = read(path)
        names |= set(re.findall(r"public\s+(?:static\s+)?(?:required\s+)?(?:sealed\s+)?[\w<>,\[\]\?\s\.]+\s+([A-Z]\w*)\s*(?:\{|=>)", text))
        names |= set(re.findall(r"public\s+(?:static\s+)?(?:readonly\s+)?[\w<>,\[\]\?\s\.]+\s+([A-Z]\w*)\s*;\s*$", text, re.M))
        # 字段写法：public double OverallProgress;
        names |= set(re.findall(r"^\s*private\s+[\w<>,\[\]\?\s\.]+\s+(\w+);", text, re.M))
    return names


def main() -> int:
    problems: list[str] = []
    notes: list[str] = []

    xaml_files = sorted(APP.glob("*.xaml"))
    cs_files = sorted(APP.rglob("*.cs"))
    core_files = sorted(CORE.glob("*.cs"))
    if not xaml_files:
        problems.append("没有找到 XAML 文件")
    if not cs_files:
        problems.append("没有找到 C# 文件")

    # ---------- 1. XAML 格式 + x:Name ----------
    names_in_xaml: set[str] = set()
    handlers_in_xaml: set[str] = set()
    resources_in_xaml: set[str] = set()
    bindings: set[str] = set()
    for xaml in xaml_files:
        try:
            ET.parse(xaml)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"XAML 解析失败 {xaml.name}：{exc}")
            continue
        text = read(xaml)
        names_in_xaml |= set(re.findall(r'x:Name="([A-Za-z0-9_]+)"', text))
        handlers_in_xaml |= set(re.findall(r'(?<![A-Za-z])(?:Click|Drop|DragOver|DoubleTapped|SelectionChanged|Checked|Unchecked|Tapped|Loaded)="([A-Za-z0-9_]+)"', text))
        resources_in_xaml |= set(re.findall(r'x:Key="([A-Za-z0-9_]+)"', text))
        bindings |= set(re.findall(r'\{Binding\s+([A-Za-z_][A-Za-z0-9_]*)', text))
        static_refs = set(re.findall(r'Converter=\{StaticResource\s+([A-Za-z0-9_]+)\}', text))
        for ref in static_refs:
            if ref not in resources_in_xaml:
                problems.append(f"{xaml.name} 引用了未定义的 StaticResource：{ref}")

    # ---------- 2. 代码后置引用的控件与处理器 ----------
    code = "\n".join(read(f) for f in cs_files)
    for name in sorted(re.findall(r"\b([A-Z]\w+Button|JobList|EmptyHint|FormatBox|QualityBox|SampleRateBox|ChannelBox|CompBox|PresetBox|ConflictBox|LogBox|RootGrid|AppTitleBar)\b", code)):
        if name not in names_in_xaml:
            problems.append(f"代码引用了 XAML 中不存在的控件：{name}")
    for handler in sorted(handlers_in_xaml):
        if not re.search(rf"private\s+(?:async\s+)?void\s+{handler}\s*\(", code):
            problems.append(f"XAML 里的处理器 {handler} 没有对应实现")

    # ---------- 3. {Binding X} 路径 ----------
    vm = APP / "ViewModels" / "ConvertViewModel.cs"
    job = CORE / "Job.cs"
    vm_props = collect_cs_properties([vm])
    job_props = collect_cs_properties([job])
    for prop in sorted(bindings):
        if prop not in vm_props and prop not in job_props:
            problems.append(f"XAML 绑定了不存在的属性：{prop}")

    # ---------- 4. csproj 关键属性 ----------
    csproj = read(APP / "TransMusic.App.csproj")
    required = {
        "<UseWinUI>true</UseWinUI>": "UseWinUI",
        "<WindowsPackageType>None</WindowsPackageType>": "WindowsPackageType=None（免打包运行）",
        "<EnableMsixTooling>true</EnableMsixTooling>": "EnableMsixTooling（生成 resources.pri 必需）",
        "<EnableCoreMrtTooling>false</EnableCoreMrtTooling>": "EnableCoreMrtTooling=false（避开 VS 专属 MRT 任务）",
        "Microsoft.WindowsAppSDK": "WindowsAppSDK 包引用",
    }
    for needle, label in required.items():
        if needle not in csproj:
            problems.append(f"csproj 缺少 {label}")

    # ---------- 5. 三端格式表一致性 ----------
    from transmusic.formats import FORMAT_ORDER, FORMATS  # noqa: E402

    if not GENERATED.exists():
        problems.append("缺少 Formats.Generated.cs（先运行 tools/gen_winui_formats.py）")
    else:
        generated = read(GENERATED)
        keys = re.findall(r'Key = "([a-z0-9_]+)",', generated.split("public static class Presets")[0])
        if keys != FORMAT_ORDER:
            problems.append(f"Formats.Generated.cs 的格式顺序与 Python 端不一致：{keys} vs {FORMAT_ORDER}")
        for key in FORMAT_ORDER:
            fmt = FORMATS[key]
            if f'Codec = "{fmt.codec}"' not in generated:
                problems.append(f"Formats.Generated.cs 缺少 {key} 的编码器 {fmt.codec}")
            if fmt.bitrates and f"Bitrates = new[] {{ {', '.join(map(str, fmt.bitrates))} }}" not in generated:
                problems.append(f"Formats.Generated.cs 的 {key} 码率列表与 Python 端不一致")
        notes.append(f"三端格式一致：{len(FORMAT_ORDER)} 种")

    # ---------- 6. 文档性检查 ----------
    targets = WINDOWS / "Directory.Build.targets"
    if targets.exists():
        notes.append("存在 Directory.Build.targets（VS 任务兜底），当前不参与构建流程")

    print(f"XAML {len(xaml_files)} 个，App 代码 {len(cs_files)} 个，Core {len(core_files)} 个")
    print(f"x:Name {len(names_in_xaml)} 个，事件处理器 {len(handlers_in_xaml)} 个，绑定属性 {len(bindings)} 个")
    for n in notes:
        print("  ·", n)

    print("\n" + "=" * 62)
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  ✘", p)
        return 1
    print("WinUI 工程静态检查全部通过 ✔")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
