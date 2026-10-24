# TransMusic · Windows 版构建说明（WinUI 3）

Windows 图形界面是 **WinUI 3（Windows App SDK）+ C#/.NET 8** 原生应用，转码引擎在 `TransMusic.Core` 类库，
界面在 `TransMusic.App`（XAML + MVVM）。三端（Windows / Android / Python CLI）共用同一份格式定义。

```
windows/
├── TransMusic.sln?                    （用 dotnet CLI 构建时不需要；Android Studio 无关）
├── TransMusic.Core/                   # 纯 .NET 8 类库，无 UI 依赖，可单独测试
│   ├── Formats.Generated.cs           ⚠ 自动生成：15 种格式 + 8 预设 + 扩展名表
│   ├── CommandBuilder.cs              ffmpeg 参数构造（三端逐行对齐）
│   ├── ConvertQueue.cs                并发队列 + 取消 + UI 线程编组
│   ├── FfmpegRunner.cs                执行 ffmpeg、解析 -progress、取消即杀进程
│   ├── FfmpegLocator.cs               找 ffmpeg：环境变量 → 程序目录 → tools → PATH
│   ├── AudioProbe.cs                  时长/码率/采样率/声道/编码/封面探测
│   ├── OutputPathResolver.cs          文件名模板、重名策略、唯一命名
│   ├── Job.cs / MediaInfo.cs / ConvertSettings.cs / QualityOptions.cs / AppSettings.cs
├── TransMusic.App/                    # WinUI 3 应用（XAML + MVVM）
│   ├── App.xaml(.cs)                  主题、品牌色、启动参数
│   ├── MainWindow.xaml(.cs)           主界面（列表 / 设置侧栏 / 操作栏 / 拖放）
│   ├── ViewModels/ConvertViewModel.cs 状态、格式联动、预设、持久化
│   ├── Converters/Converters.cs       bool→Visibility、状态色
│   ├── Assets/app.ico                 应用图标
│   ├── ffmpeg/ffmpeg.exe              引擎（构建时复制到输出目录）
│   └── TransMusic.App.csproj
├── TransMusic.Tests/                  # 控制台自检 + 参数对比工具
└── publish/                           # 已发布的成品（双击 TransMusic.exe 即可运行）
```

## 一、直接运行

**`windows\publish\TransMusic.exe`** —— 免安装，双击即可（内置 ffmpeg 与 Windows App SDK 运行时文件）。

> 需要 Windows 10 1809+ / Windows 11。首次启动如果没反应，见文末「常见问题」。

## 二、自己构建

### 前置

- **.NET 8 SDK**（`dotnet --version` 能输出 8.x）
- 不需要 Visual Studio：工程已按「无 VS」环境配置好（见下节说明）
- Windows 10 SDK 的构建工具由 NuGet 自动还原，无需单独安装（`makepri.exe` 来自 `Microsoft.Windows.SDK.BuildTools`）

### 步骤

```powershell
# 1) 放好 ffmpeg（二选一）
python tools\fetch_ffmpeg.py                     # 用 pip 的 imageio-ffmpeg 自带二进制
python tools\fetch_ffmpeg.py D:\ffmpeg\bin\ffmpeg.exe

# 2) 编译 + 运行（开发）
cd windows
dotnet build TransMusic.App\TransMusic.App.csproj
dotnet run  --project TransMusic.App\TransMusic.App.csproj

# 3) 发布成可分发目录（含 Windows App SDK 运行时，免安装）
dotnet publish TransMusic.App\TransMusic.App.csproj -c Release -r win-x64 --self-contained false
# 产物：windows\TransMusic.App\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish\
```

### 关键构建属性（`TransMusic.App.csproj`）

| 属性 | 值 | 为什么 |
| --- | --- | --- |
| `TargetFramework` | `net8.0-windows10.0.19041.0` | WinUI 3 需要的 Windows TFM |
| `UseWinUI` | `true` | 启用 XAML 编译（XamlCompiler） |
| `WindowsPackageType` | `None` | 免 MSIX 打包，直接输出可运行 exe |
| `WindowsAppSDKSelfContained` | `true` | 把 Windows App SDK 运行时随程序复制，目标机器无需预装 |
| `EnableMsixTooling` | `true` | **必须**：只有走 MSIX 工具链才会生成 `resources.pri`，否则启动崩溃（0xC000027B） |
| `EnableCoreMrtTooling` | `false` | **必须**（无 VS 环境）：默认的 MrtCore 工具链会调用 Visual Studio 专属 MSBuild 任务（`Microsoft.Build.AppxPackage.dll`），没装 VS 时必然失败 |
| `ImplicitUsings` | `enable` | `List<>`/`Task` 等常用类型免 using |

这两条组合是「没有 Visual Studio 也能构建 WinUI 3」的关键：
`EnableCoreMrtTooling=false` 让构建走 NuGet 自带的 `Microsoft.Build.Msix` 工具链，
`EnableMsixTooling=true` 让这套工具链真正执行 PRI 生成。

## 三、功能

- 拖拽文件/文件夹到窗口（递归扫描、去重），也可「添加文件/文件夹」；可拖入视频只取音轨
- 批量队列，并发 1–16；任一任务可取消，取消即 `Kill(entireProcessTree)` 并删除半成品
- 15 种目标格式 + 质量/位深/压缩级别联动；8 个常用预设一键套用
- 输出：源目录或指定目录、文件名模板 `{name} {artist} {title} {album} {index} {ext}`、重名策略（重命名/覆盖/跳过）
- 默认复制标签与内嵌封面；双击任务看详情（编码、时长、码率、采样率、声道、封面、输出路径）
- 参数记进 `%APPDATA%\TransMusic\settings.json`，下次启动沿用
- 支持「发送到」/命令行直接打开：`TransMusic.exe D:\Music` 或 `TransMusic.exe a.mp3 b.flac`

## 四、自检与一致性

```powershell
cd windows
dotnet run --project TransMusic.Tests\TransMusic.Tests.csproj -c Release -- `
    selftest --ffmpeg "..\..\samples\ffmpeg.exe"     # 34 项检查：真转 15 种格式、取消、命名、模板
```

```bash
python tests/parity_winui_test.py    # C# 与 Python 的 ffmpeg 参数逐条对比（236 用例）
python tests/winui_check.py          # XAML/绑定/事件/x:Name/csproj 属性/格式表漂移 静态检查
python tests/smoke_test.py           # Python 引擎的 15 格式矩阵与互转链
```

## 五、打包成单文件 exe

    powershell -ExecutionPolicy Bypass -File toolsuild_single_exe.ps1 -Version 1.2.0
    # 产物：release\TransMusic-1.2.0-standalone.exe（约 71 MB）

流程：dotnet publish 出完整应用 → Compress-Archive 成 payload.zip → 作为 EmbeddedResource
塞进 TransMusic.Launcher → 用 PublishSingleFile + PublishTrimmed 输出单个 exe。

注意事项：

- **不要**直接给 TransMusic.App 加 PublishSingleFile=true。Windows App SDK 不支持，
  启动会抛 0xe0434352（原生库与 resources.pri 找不到），已实测确认。
- 启动器只负责解压 + 拉起主程序，随后立即退出，不常驻内存。
- 解压目标 %LOCALAPPDATA%\TransMusicpp\<版本>；版本号取自启动器程序集版本，
  升级后自动换目录并清理旧目录。
- 载荷里必须包含 ffmpegfmpeg.exe（tools/fetch_ffmpeg.py 保证），否则单文件版没有转码引擎。

## 六、常见问题

- **双击没反应 / 闪退（0xC000027B）**：`resources.pri` 没生成。确认 csproj 里
  `EnableMsixTooling=true` 且输出目录存在 `resources.pri`；用 `dotnet build` 而不是手工拷贝文件。
- **提示找不到 ffmpeg**：把 `ffmpeg.exe` 放到 `TransMusic.exe` 同目录、或同目录的 `ffmpeg\` 子目录、
  或设环境变量 `TRANSMUSIC_FFMPEG` 指向它。发布目录里已经自带。
- **构建报 `Microsoft.Build.AppxPackage.dll 找不到`**：`EnableCoreMrtTooling` 又被打开了（比如从别处复制了 csproj），
  改回 `false` 即可。
- **界面是深色固定主题**：与 Android / CLI 保持同一套视觉；想跟随系统可改 `MainWindow.xaml` 里
  `RootGrid` 的 `RequestedTheme`。
- **任务全部瞬间失败**：多半是引擎路径不对（看「日志」面板的错误信息）；
  也可能是跨线程改绑定对象——`ConvertQueue` 的 `marshal` 参数必须传 UI 线程调度器（`MainWindow` 里已处理）。
- **想打包成 MSIX 上架**：把 `WindowsPackageType` 改成 `MSIX`、`EnableMsixTooling` 保持 `true`，
  补 `Package.appxmanifest`，再 `dotnet publish -p:GenerateAppxPackageOnBuild=true`（需要签名证书）。
