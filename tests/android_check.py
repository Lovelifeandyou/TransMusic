"""Android 工程的静态自检（本机没有 Android SDK，用静态检查替代编译器）。

覆盖：
  1. 所有 res XML / Manifest 是否格式正确；@color / @string / @style 引用是否都存在
  2. Kotlin 里引用的 R.id / R.string / R.color / R.layout 是否真实存在
     （MD3E/Compose 版已无 layout 与 view id，出现即说明残留了 View 代码）
  3. Kotlin 花括号/圆括号是否配平（跳过字符串与注释）
  4. package 声明与目录一致；跨包 import 的自家类型/成员是否存在
  5. 依赖一致性：用了 androidx.compose.* 就必须开启 compose 构建；已删除的 View 依赖不得再 import
  6. 图标名必须落在安全清单内（拼错 Icon 名在真机编译时才会报错，这里提前拦）
  7. Formats.kt 是否与 Python 端一致（防止两端参数漂移）

用法：python tests/android_check.py
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
SRC = ANDROID / "app" / "src" / "main"
KOTLIN_DIR = SRC / "java"
RES = SRC / "res"
GRADLE = ANDROID / "app" / "build.gradle.kts"

FRAMEWORK_STYLE_PREFIXES = (
    "Widget.", "Theme.Material", "TextAppearance.", "ThemeOverlay.", "ShapeAppearance.",
)

# 已经移除的 View 系依赖：代码里再出现就是编译不过的残留
FORBIDDEN_IMPORTS = (
    "com.google.android.material.",
    "androidx.recyclerview.",
    "androidx.appcompat.",
    "androidx.constraintlayout.",
)

# material-icons-core 里确定存在的图标（用其它名字请先确认 material-icons-extended）
CORE_ICONS = {
    "Add", "ArrowBack", "ArrowDropDown", "ArrowForward", "Build", "Call", "Check", "CheckCircle",
    "Clear", "Close", "Create", "DateRange", "Delete", "Done", "Edit", "Email", "ExitToApp",
    "Face", "Favorite", "Home", "Info", "KeyboardArrowDown", "KeyboardArrowLeft",
    "KeyboardArrowRight", "KeyboardArrowUp", "List", "LocationOn", "Lock", "MailOutline", "Menu",
    "MoreVert", "Notifications", "Person", "Phone", "PlayArrow", "Refresh", "Search", "Send",
    "Settings", "Share", "ShoppingCart", "Star", "ThumbUp", "Warning",
}


def rel(p: Path) -> str:
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def strip_kotlin(code: str) -> str:
    """去掉注释与字符串字面量，方便做括号配平检查。"""
    out = []
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c == "/" and i + 1 < n and code[i + 1] == "/":
            while i < n and code[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and code[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if code[i] == "/" and i + 1 < n and code[i + 1] == "*":
                    depth += 1
                    i += 2
                elif code[i] == "*" and i + 1 < n and code[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif code.startswith('"""', i):
            i += 3
            while i < n and not code.startswith('"""', i):
                i += 1
            i += 3
        elif c == '"':
            i += 1
            while i < n and code[i] != '"':
                if code[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and code[i] != "'":
                if code[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main() -> int:
    problems: list[str] = []
    notes: list[str] = []

    # ---------- 1. XML 与资源 ----------
    xml_files = sorted(RES.rglob("*.xml")) + [SRC / "AndroidManifest.xml"] + sorted(ANDROID.glob("*.xml"))
    ids: set[str] = set()
    styles_used: set[str] = set()
    layouts: set[str] = set()
    for f in xml_files:
        try:
            ET.parse(f)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"XML 解析失败 {rel(f)}：{exc}")
            continue
        if f.parent.name.startswith("layout") and f.parent.parent == RES:
            layouts.add(f.stem)
        text = f.read_text("utf-8")
        ids |= set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', text))
        styles_used |= set(re.findall(r'style="@style/([A-Za-z0-9_.]+)"', text))

    values_text = "\n".join(f.read_text("utf-8") for f in (RES / "values").glob("*.xml"))
    strings = set(re.findall(r'<string name="([A-Za-z0-9_]+)"', values_text))
    colors = set(re.findall(r'<color name="([A-Za-z0-9_]+)"', values_text))
    styles = set(re.findall(r'<style name="([A-Za-z0-9_.]+)"', values_text))

    for f in xml_files:
        text = f.read_text("utf-8")
        for name in re.findall(r'@color/([A-Za-z0-9_]+)', text):
            if name not in colors:
                problems.append(f"{rel(f)} 引用了未定义的 @color/{name}")
        for name in re.findall(r'@string/([A-Za-z0-9_]+)', text):
            if name not in strings:
                problems.append(f"{rel(f)} 引用了未定义的 @string/{name}")
        for name in re.findall(r'@style/([A-Za-z0-9_.]+)', text):
            if name not in styles and not name.startswith(FRAMEWORK_STYLE_PREFIXES):
                problems.append(f"{rel(f)} 引用了未定义的 style：{name}")
    for st in sorted(styles_used):
        if st not in styles and not st.startswith(FRAMEWORK_STYLE_PREFIXES):
            problems.append(f"使用了未定义的 style：{st}")

    # 主题必须被 Manifest 引用，否则启动会白屏/报错
    manifest = (SRC / "AndroidManifest.xml").read_text("utf-8")
    theme_ref = re.search(r'android:theme="@style/([A-Za-z0-9_.]+)"', manifest)
    if not theme_ref:
        problems.append("AndroidManifest.xml 未设置 android:theme")
    elif theme_ref.group(1) not in styles:
        problems.append(f"Manifest 引用了未定义的主题：{theme_ref.group(1)}")

    # ---------- 2~6. Kotlin ----------
    kt_files = sorted(KOTLIN_DIR.rglob("*.kt"))
    declared: set[str] = set()
    packages: dict[Path, str] = {}
    for f in kt_files:
        code = f.read_text("utf-8")
        pkg = re.search(r"^package\s+([\w.]+)", code, re.M)
        packages[f] = pkg.group(1) if pkg else ""
        declared |= set(
            re.findall(r"\b(?:data\s+class|class|object|enum\s+class|interface)\s+([A-Za-z0-9_]+)", code)
        )
        # 顶层 val / fun（缩进为 0 的行）
        declared |= set(re.findall(r"^(?:private\s+|internal\s+|public\s+)?(?:val|fun)\s+([A-Za-z0-9_]+)", code, re.M))

    uses_compose = False
    for f, pkg in packages.items():
        expected = ".".join(f.relative_to(KOTLIN_DIR).parent.parts)
        if pkg != expected:
            problems.append(f"{rel(f)} 的 package（{pkg}）与目录（{expected}）不一致")

        code = f.read_text("utf-8")
        scan = code.replace("android.R.", "frameworkR.")

        for bad in FORBIDDEN_IMPORTS:
            if re.search(rf"^import\s+{re.escape(bad)}", code, re.M):
                problems.append(f"{rel(f)} 仍 import 了已移除的 View 依赖：{bad}*")
        if "androidx.compose." in code:
            uses_compose = True

        # R.* 引用（Compose 版应当完全没有）
        for rid in set(re.findall(r"(?<![A-Za-z_])R\.id\.([A-Za-z0-9_]+)", scan)):
            if rid not in ids:
                problems.append(f"{rel(f)} 使用了不存在的 R.id.{rid}")
        for rs in set(re.findall(r"(?<![A-Za-z_])R\.string\.([A-Za-z0-9_]+)", scan)):
            if rs not in strings:
                problems.append(f"{rel(f)} 使用了不存在的 R.string.{rs}")
        for rc in set(re.findall(r"(?<![A-Za-z_])R\.color\.([A-Za-z0-9_]+)", scan)):
            if rc not in colors:
                problems.append(f"{rel(f)} 使用了不存在的 R.color.{rc}")
        for rl in set(re.findall(r"(?<![A-Za-z_])R\.layout\.([A-Za-z0-9_]+)", scan)):
            if rl not in layouts:
                problems.append(f"{rel(f)} 使用了不存在的 R.layout.{rl}（Compose 版不应再引用布局）")

        # 跨包 import 的自家类型/成员是否存在
        for imp in re.findall(r"^import\s+(com\.transmusic\.converter\.[\w.]+)", code, re.M):
            name = imp.rsplit(".", 1)[-1]
            if name == "R":
                continue  # AAPT 生成
            if name not in declared:
                problems.append(f"{rel(f)} 导入了未声明的类型/成员：{imp}")

        # 图标名安全清单
        for icon in set(re.findall(r"Icons\.(?:Default|Filled|Outlined|Rounded|TwoTone|Sharp)\.([A-Za-z0-9_]+)", code)):
            if icon not in CORE_ICONS:
                problems.append(f"{rel(f)} 使用了非核心图标 Icons.*.{icon}（需确认 material-icons-extended 中存在）")

        stripped = strip_kotlin(code)
        for opener, closer, label in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
            if stripped.count(opener) != stripped.count(closer):
                problems.append(
                    f"{rel(f)} {label}不配平：{stripped.count(opener)} vs {stripped.count(closer)}"
                )

    # 依赖一致性
    gradle_text = GRADLE.read_text("utf-8")
    if uses_compose:
        if "compose = true" not in gradle_text:
            problems.append("Kotlin 用了 Compose，但 app/build.gradle.kts 没有开启 buildFeatures.compose")
        if "compose-bom" not in gradle_text:
            problems.append("Kotlin 用了 Compose，但依赖里没有 compose-bom")
        if "org.jetbrains.kotlin.plugin.compose" not in (ANDROID / "build.gradle.kts").read_text("utf-8"):
            problems.append("缺少 Kotlin Compose 编译器插件（org.jetbrains.kotlin.plugin.compose）")
        notes.append("前端：Jetpack Compose（MD3E）")

    # ---------- 7. 与 Python 端一致性 ----------
    sys.path.insert(0, str(ROOT))
    from transmusic.formats import FORMAT_ORDER, FORMATS  # noqa: E402

    formats_kt = KOTLIN_DIR / "com" / "transmusic" / "converter" / "model" / "Formats.kt"
    kt_text = formats_kt.read_text("utf-8") if formats_kt.exists() else ""
    if not kt_text:
        problems.append("缺少 Formats.kt（请先运行 tools/gen_android_formats.py）")
    else:
        formats_block = kt_text.split("object Presets")[0]
        keys = re.findall(r'key = "([a-z0-9_]+)",', formats_block)
        if keys != FORMAT_ORDER:
            problems.append(f"Formats.kt 的格式顺序与 Python 端不一致：{keys} vs {FORMAT_ORDER}")
        for key in FORMAT_ORDER:
            fmt = FORMATS[key]
            if f'codec = "{fmt.codec}"' not in kt_text:
                problems.append(f"Formats.kt 缺少 {key} 的编码器 {fmt.codec}")
            if fmt.bitrates and f"bitrates = listOf({', '.join(map(str, fmt.bitrates))})" not in kt_text:
                problems.append(f"Formats.kt 的 {key} 码率列表与 Python 端不一致")
        notes.append(f"两端格式一致：{len(FORMAT_ORDER)} 种")

    print(f"Kotlin 文件 {len(kt_files)} 个，XML {len(xml_files)} 个，声明类型/成员 {len(declared)} 个")
    print(f"R.id {len(ids)} 个，strings {len(strings)} 个，colors {len(colors)} 个，layout {len(layouts)} 个")
    for n in notes:
        print("  ·", n)

    print("\n" + "=" * 62)
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print("  ✘", p)
        return 1
    print("Android 工程静态检查全部通过 ✔")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
