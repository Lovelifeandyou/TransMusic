# 音频格式转换器 · TransMusic

**Windows（WinUI 3）+ Android（MD3E / Jetpack Compose）** 双端音频格式互转工具，外加一套 Python 命令行工具作为批量引擎与测试基准。
三端共用同一份格式与参数定义，同一份源文件在任意端都会得到**完全相同的 ffmpeg 编码参数**（有自动化测试逐条守着）。

| 平台 | 形态 | 引擎 | 状态 |
| --- | --- | --- | --- |
| Windows 10/11 | **WinUI 3 原生应用**（C# / .NET 8 / Windows App SDK） | 内置 ffmpeg 7.1 | ✅ 已构建、已实际运行验证 |
| Android 7.0+ | **原生 App**（Kotlin + MD3E / Jetpack Compose） | FFmpegKit（audio） | ✅ 工程完整，静态检查 + 参数一致性通过 |
| 任意平台 | Python 命令行（批量、脚本化） | ffmpeg 7.1 | ✅ 已实测 |

![Windows 版界面](docs/windows-winui3.png)

支持互转的格式：**MP3 · AAC/M4A · ALAC/M4A · FLAC · WAV · AIFF · OGG Vorbis · Opus · WMA · WavPack · TTA · AC3 · E-AC3 · MP2 · Speex**（15 种输出）。
输入只要是 ffmpeg 能解码的就行，**视频文件也能只抽音轨**。

---

## 一、Windows 版（WinUI 3）

**直接用**：双击 `windows\publish\TransMusic.exe`（免安装，内置 ffmpeg 与 Windows App SDK 运行时）。

- 拖拽文件/文件夹进窗口，或「添加文件/文件夹」；支持把文件拖到 exe 上直接打开
- 批量队列、并发 1–16、逐条进度、随时取消（杀 ffmpeg 进程 + 删半成品）
- 15 种格式 × 质量/位深/压缩级别联动，8 个预设，文件名模板，重名策略
- 默认保留标签与内嵌封面；双击任务看完整详情；参数自动记住

构建与发布细节见 **[BUILD-WINDOWS.md](BUILD-WINDOWS.md)**（含「没有 Visual Studio 也能构建」的关键配置）。

## 二、Android 版（MD3E 前端）

原生 Kotlin + **Jetpack Compose**，界面按 **MD3E（Material Design 3 Expressive）** 实现，工程在 `android/`。
构建步骤见 **[BUILD-ANDROID.md](BUILD-ANDROID.md)**。

- 同样 15 种输出格式、同样的质量档位与预设
- MD3E 视觉：大色块 + 26–34dp 圆角容器、Expressive 字体、弹簧动效、胶囊选项组、BottomSheet 设置面板、动态取色
- 1–8 并发、单任务取消、写 `Music/TransMusic`（Android 10+ 免存储权限）或任选文件夹、保留标签与封面

## 三、Python 命令行（批量 / 脚本）

```bash
python main.py --list-formats                      # 全部格式与可用参数
python main.py -f flac -o D:\out song.mp3
python main.py -f m4a_aac -b 256 D:\Music          # 整目录递归
python main.py -f mp3 --vbr 0 --template "{artist} - {title}" E:\CD
python main.py -f wav --bit-depth 24 --sr 96000 --ch 2 in.flac
python main.py -f opus -j 4 -n "D:\in"             # -n 只打印命令不执行
```

## 四、三端如何保持一致

1. **单一数据源**：`transmusic/formats.py` 定义全部格式/码率/位深/压缩级别/预设。
   - → Android：`tools/gen_android_formats.py` 生成 `model/Formats.kt`
   - → Windows：`tools/gen_winui_formats.py` 生成 `TransMusic.Core/Formats.Generated.cs`
2. **命令构造逐行对齐**：`engine.py::build_command` / `CommandBuilder.kt` / `CommandBuilder.cs` 三个实现分支一一对应
   （CBR/VBR、位深→`-sample_fmt`、压缩级别、采样率吸附、声道上限、封面 `attached_pic`、标签映射、mp3 的 ID3 选项）。
3. **自动化对比**：同一批 236 个用例（15 格式 × 多组参数 × 4 种源特征）分别喂给 C# 与 Kotlin 实现，
   与 Python 基准逐参数 diff。

```
$ python tests/parity_winui_test.py     →  WinUI 端与 Python 端参数完全一致 ✔
$ python tests/parity_test.py           →  Android 端与 Python 端参数完全一致 ✔
```

> 唯一有意的差别：进度上报通道。Windows 用 `-progress pipe:1` 解析 stdout，Android 用 FFmpegKit 的 statistics 回调，
> 所以该参数不参与对比（`report_progress=False`）。

## 五、测试

| 命令 | 覆盖内容 | 结果 |
| --- | --- | --- |
| `python tests/smoke_test.py` | Python 引擎：15 格式矩阵、`flac→mp3→ogg→opus→wav` 互转链、标签封面、预设、取消、进度 | 通过 ✔ |
| `python tests/parity_winui_test.py` | C# vs Python：236 用例逐参数 diff | 100% 一致 ✔ |
| `python tests/parity_test.py` | Kotlin vs Python：236 用例逐参数 diff | 100% 一致 ✔ |
| `python tests/winui_check.py` | WinUI 工程：XAML 合法性、x:Name/事件处理器/绑定属性是否存在、csproj 关键属性、格式表漂移 | 通过 ✔ |
| `python tests/android_check.py` | Android 工程：XML、R 资源、括号配平、包名、依赖与 Compose 配置、图标名、格式表漂移 | 通过 ✔ |
| `python tests/kotlin_syntax_check.py` | kotlinc 语法级编译 18 个 Kotlin 文件 | 通过 ✔ |
| `dotnet run --project windows/TransMusic.Tests -- selftest --ffmpeg <path>` | **WinUI 引擎真跑**：15 种格式全部转换并回读校验、命名冲突、模板、队列完成/取消、半成品清理 | 34/34 通过 ✔ |

## 六、目录结构

```
transmusic/                 Python 引擎（格式数据源 + CLI + 测试基准）
  formats.py                15 种格式 / 8 预设 / 扩展名表 —— 三端的唯一数据源
  engine.py                 ffmpeg 定位、命令构建、探测、执行、进度解析
  cli.py                    命令行批量模式
main.py                     命令行入口

windows/                    Windows 版（WinUI 3）
  TransMusic.Core/          纯 .NET 8 引擎类库（命令构造、队列、探测、命名）
  TransMusic.App/           WinUI 3 界面（XAML + MVVM，深色 Fluent）
  TransMusic.Tests/         引擎自检 + 参数对比工具（控制台）
  publish/                  已发布成品：TransMusic.exe

android/                    Android 版（Kotlin + Compose + MD3E）
  app/src/main/java/com/transmusic/converter/
    model/Formats.kt        ⚠ 自动生成
    engine/                 CommandBuilder / FfmpegEngine / AudioProbe
    core/                   队列、状态、ViewModel、偏好
    ui/                     主题、主界面、设置面板、任务卡

tools/                      生成器与开发脚本（图标、三端格式表、ffmpeg 获取、截图）
tests/                      全部自动化测试
docs/                       截图
```

## 七、常见问题

- **Windows 双击没反应**：确认用的是 `windows\publish\TransMusic.exe`（同目录需有 `resources.pri` 与 dll）。
- **提示找不到 ffmpeg**：放到 exe 同目录 / 同目录 `ffmpeg\` / PATH，或设 `TRANSMUSIC_FFMPEG`。
- **转 MP3 报采样率不支持**：会把非常规采样率吸附到编码器支持的值（96 kHz → 48 kHz），无损格式原样保留。
- **元数据丢了**：目标格式本身不支持标签（WAV / AIFF / AC3 / MP2）时会丢弃，这是容器限制。
- **WAV 32 bit 是浮点**（`pcm_f32le`）；要整数 24 bit 请选 24 bit。
- **Android 转码失败**：确认 `abiFilters` 覆盖手机架构（真机多为 `arm64-v8a`）；依赖拉不到时见 BUILD-ANDROID.md。
