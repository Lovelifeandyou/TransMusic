"""清理 Android Studio 模板残留，并修复被改动的 manifest / theme。

背景：android/ 目录曾被 Android Studio 以「新建项目」方式打开，
它追加了一套 com.example.transmusic 模板（含浅色主题、默认图标、测试样板），
同时给 Activity 加了 android:theme="@style/Theme.Transmusic"（浅色），
会让我们的 MD3E 深色界面失效。这里把模板痕迹全部移除。
"""
from __future__ import annotations

import shutil
from pathlib import Path

ANDROID = Path(r"E:\transmusic\android")
APP = ANDROID / "app" / "src" / "main"
RES = APP / "res"

removed: list[str] = []


def drop(path: Path) -> None:
    if not path.exists():
        return
    try:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
        removed.append(str(path.relative_to(ANDROID)))
    except PermissionError:
        # 被 Android Studio / Gradle 守护进程占用的缓存目录，跳过即可（不影响工程正确性）
        print(f'  ! 跳过被占用的 {path.relative_to(ANDROID)}（可稍后手动删除）')


# 1) 模板 Kotlin 源码与测试
drop(APP / "java" / "com" / "example")
drop(ANDROID / "app" / "src" / "androidTest")
drop(ANDROID / "app" / "src" / "test")

# 2) 模板图标与规则文件
for name in ("mipmap-anydpi-v26",):
    drop(RES / name)
for folder in RES.glob("mipmap-*"):
    for pattern in ("*.webp",):
        for f in folder.glob(pattern):
            drop(f)
for f in (RES / "drawable").glob("ic_launcher_*"):
    drop(f)
drop(RES / "xml" / "backup_rules.xml")
drop(RES / "xml" / "data_extraction_rules.xml")
drop(APP / "keepRules")
drop(ANDROID / "gradle" / "libs.versions.toml")

# 3) IDE / 构建缓存 / 机器相关文件
for name in (".idea", ".gradle", "local.properties", "build", ".kotlin"):
    drop(ANDROID / name)
drop(ANDROID / "app" / "build")

# 4) manifest：去掉模板加的浅色主题
manifest = APP / "AndroidManifest.xml"
text = manifest.read_text("utf-8")
text = text.replace('            android:theme="@style/Theme.Transmusic"\n', "")
text = text.replace("\n\n            <intent-filter>", "\n            <intent-filter>")
manifest.write_text(text, "utf-8")

# 5) themes.xml：去掉模板的浅色 Theme.Transmusic
themes = RES / "values" / "themes.xml"
text = themes.read_text("utf-8")
text = text.replace('\n    <style name="Theme.Transmusic" parent="android:Theme.Material.Light.NoActionBar" />\n', "")
themes.write_text(text, "utf-8")

# 6) Gradle wrapper：AGP 8.5 需要 Gradle 8.x，模板写的是 9.6
wrapper = ANDROID / "gradle" / "wrapper" / "gradle-wrapper.properties"
if wrapper.exists():
    text = wrapper.read_text("utf-8")
    lines = []
    for line in text.splitlines():
        if line.startswith("distributionUrl="):
            lines.append("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip")
        elif line.startswith("distributionSha256Sum="):
            continue          # 校验和是 9.6 的，删掉避免下载校验失败
        else:
            lines.append(line)
    wrapper.write_text("\n".join(lines) + "\n", "utf-8")

print("已移除：")
for item in removed:
    print("  -", item)
if not removed:
    print("  （无）")
print("已修复 AndroidManifest.xml 与 values/themes.xml")
print("已把 Gradle wrapper 固定到 8.7（与 AGP 8.5.2 匹配）")
