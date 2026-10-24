# TransMusic · Android 版（MD3E / Material 3 Expressive）构建说明

Android 端是**原生 Kotlin + Jetpack Compose** 工程，界面采用 **MD3E（Material Design 3 Expressive）** 设计语言；
转码能力与 Windows 桌面版对齐：两端共用同一份格式/参数定义（`Formats.kt` 由 Windows 端的 `formats.py` 自动生成），
同一份源文件在两端会得到**完全相同的 ffmpeg 参数**。

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/build.gradle.kts                  Compose + MD3E + FFmpegKit 依赖
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/transmusic/converter/
    │   ├── MainActivity.kt                单 Activity，setContent { TransMusicTheme { ConvertScreen } }
    │   ├── model/Formats.kt               ⚠ 自动生成：15 种格式 + 8 个预设 + 扩展名表
    │   ├── engine/CommandBuilder.kt       ffmpeg 参数构造（与 Windows 版逐行对齐）
    │   ├── engine/ConvertSettings.kt      转换参数
    │   ├── engine/AudioProbe.kt           时长/码率/采样率/声道/封面探测
    │   ├── engine/FfmpegEngine.kt         FFmpegKit 执行 + 进度 + 取消
    │   ├── core/ConvertQueue.kt           并发队列（信号量限流、状态流、日志）
    │   ├── core/ConvertViewModel.kt       StateFlow 暴露给 Compose + 参数持久化
    │   ├── core/JobModels.kt              任务模型
    │   ├── core/Prefs.kt                  参数记忆
    │   ├── util/WorkFiles.kt              Uri → 缓存文件、临时产物清理
    │   ├── util/OutputSink.kt             写音乐库 / 写自选目录（MediaStore + SAF）
    │   └── ui/
    │       ├── theme/Theme.kt             MD3E 主题：配色 / Expressive 字体 / 大圆角 / 弹簧动效
    │       ├── ConvertScreen.kt           主界面（大标题 + 输出摘要大色块 + 任务卡 + 底部主按钮）
    │       ├── Expressive.kt              SectionCard / ChoiceChips / StatusPill / PulsingDot
    │       ├── SettingsSheet.kt           设置 BottomSheet（格式/质量/位深/压缩/采样率/声道/预设）
    │       ├── SettingsState.kt           界面参数状态 + 与 ConvertSettings 的映射
    │       └── JobCard.kt                 任务卡与空状态
    └── res/                               仅图标 + 深色窗口壳主题（其余样式全在 Compose 里）
```

## 一、构建 APK

1. 安装 **Android Studio**（Ladybug 2024.2 及以上，自带 JDK 17 与 SDK 34）。
2. `File → Open` 选择本仓库的 `android/` 目录（**不是**仓库根目录）。
3. 首次同步会补齐 Gradle Wrapper 并下载依赖（需能访问 `google()` 与 `mavenCentral()`）。
4. 连手机（开启 USB 调试）→ 点 ▶ Run，或 `Build → Build Bundle(s)/APK(s) → Build APK(s)`，
   产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

命令行构建：

```bash
cd android
gradle wrapper --gradle-version 8.7      # 首次生成 wrapper
./gradlew assembleDebug
```

**环境要求**：`compileSdk 34`、`minSdk 24`（Android 7.0+）、`targetSdk 34`；ABI 默认 `arm64-v8a` / `armeabi-v7a` / `x86_64`。

## 二、MD3E（Material 3 Expressive）在前端怎么落地的

| MD3E 特征 | 本项目实现 |
| --- | --- |
| 大色块 + 大圆角容器 | `ui/theme/Theme.kt` 的 `Shapes`（10/14/20/26/34dp）+ `surfaceContainer*` 容器色阶；`SectionCard`、`OutputSummaryCard` 直接用它 |
| Expressive 字体（更重、更紧） | 自定义 `Typography`：headline 用 Bold + 负字距，label 用 SemiBold 12–14sp |
| 弹簧动效（bouncy） | `object Motion`：`snappy` / `bouncy` / `gentle` 三档 `spring()`；主按钮按下 `scale 0.955`、列表进度胶囊、`AnimatedVisibility` 展开日志 |
| 胶囊式选项组（segmented / chip group） | `ChoiceChips`（FlowRow + FilterChip）：格式、质量、采样率、声道、保存位置、预设全部是胶囊 |
| 动态取色（Material You） | `dynamicDarkColorScheme()`（Android 12+ 跟随壁纸）；老系统回落品牌深色方案 |
| 底部主操作 + BottomSheet | `Scaffold.bottomBar` 里的大号 `Button`（54dp 高）+ `ModalBottomSheet` 设置面板 |
| 状态表达 | `StatusPill`（色块胶囊）+ `PulsingDot`（转换中的呼吸点）+ 圆角进度条 |

### 想用官方 expressive 组件（`ButtonGroup` / `LoadingIndicator` / `MaterialExpressiveTheme`）

这些 API 目前只在 `material3` 的 1.4.0-alpha 线里，属于 `@ExperimentalMaterial3ExpressiveApi`。
本工程为了「一定能编译」，用的是 **稳定版 material3 + 自己实现的 Expressive 组件**。想升级到官方组件时：

```kotlin
// app/build.gradle.kts
implementation("androidx.compose.material3:material3:1.4.0-alpha17")  // 换成你能拉到的最新 alpha
```

```kotlin
// ui/theme/Theme.kt 里可改用官方入口
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransMusicTheme(content: @Composable () -> Unit) =
    MaterialExpressiveTheme(colorScheme = ..., typography = ..., motionScheme = ..., content = content)
```

界面层已经把所有样式收敛到 `ui/theme/Theme.kt` 与 `ui/Expressive.kt`，换官方组件只需要改这两处。

## 二之二、DSD 输入与外观

- **DSD（.dsf/.dff）**：FFmpegKit 自带 DSD 解码器，App 会按 `DsdSupport` 把 DSD64/128/256 降到
  88.2k / 176.4k / 352.8k 的 PCM 并在降采样前加 `lowpass=f=30000`；DSD 只作输入（ffmpeg 无 DSD 编码器）。
- **明暗主题**：设置面板「外观」三选（跟随系统 / 浅色 / 深色），存 `Prefs.themeMode`，
  Compose 即时重组；系统栏图标颜色随主题切换（`SystemBarStyle.dark/light`）。
- **封面预览**：列表项用 `JobCover` 从 `MediaMetadataRetriever.embeddedPicture` 取内嵌封面，
  无封面时显示音符占位。

## 三、依赖说明

转码引擎用 **FFmpegKit（audio 包）**，自带 lame / libopus / libvorbis / libspeex / wavpack / opencore-amr / twolame 等编码库，
并内置 aac / flac / alac / ac3 / wma / pcm 编解码器，覆盖桌面版全部 15 种目标格式。

```kotlin
implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")
```

### 依赖不可解析时（ffmpeg-kit 官方已归档）

按顺序试这三种办法，都只改 `app/build.gradle.kts` 一行：

| 方案 | 写法 |
| --- | --- |
| A. 换 LTS 音频包 | `implementation("com.arthenica:ffmpeg-kit-lts-audio:6.0-2.LTS")` |
| B. 换完整包 | `implementation("com.arthenica:ffmpeg-kit-full:6.0-2")`（体积更大，格式支持更全） |
| C. 离线 AAR | 从 Maven Central 下 `ffmpeg-kit-audio-6.0-2.aar` 放进 `app/libs/`，写 `implementation(files("libs/ffmpeg-kit-audio-6.0-2.aar"))` |

引擎层已隔离在 `engine/FfmpegEngine.kt`（只有 ~80 行），换库只需改这一个文件的类名。

## 四、Android 版的转换流程

```
选中文件(SAF) → 复制到 cacheDir（ffmpeg 需要真实路径）
              → 按 Formats/ConvertSettings 生成参数
              → FFmpegKit 执行（统计回调算进度，可随时取消）
              → 写入 Music/TransMusic（MediaStore，Android 10+ 免权限）
                 或写入自选文件夹（SAF DocumentFile）
              → 清理缓存
```

- **输入**：`OpenMultipleDocuments`，音频与视频都收（视频自动只取音轨）。
- **输出**：默认 `Music/TransMusic`；也可选任一文件夹（走 SAF，可持久授权）。
- **并发**：1–8 个任务并行（协程信号量），单个任务取消不影响其他任务。
- **重名**：自动追加 `(1)`、`(2)`，不会覆盖已有文件。
- **权限**：Android 10+ 完全不需要存储权限；只有 Android 9 及以下写系统音乐库时才申请旧的写权限。

## 五、三端一致性保障

- model/Formats.kt 由 	ools/gen_android_formats.py 依据 	ransmusic/formats.py 生成，改格式只改 Python 端。
- 	ests/parity_test.py 用 kotlinc 编译 Android 端真实的 CommandBuilder，与 Python 基准在 **236** 个用例上逐参数 diff，
  当前 100% 一致；同一批用例也喂给 Windows 端（	ests/parity_winui_test.py），三端参数完全对齐。
- 	ests/android_check.py：XML 合法性、R 资源引用、括号配平、package 与目录一致、跨包 import、
  依赖与 Compose 配置、图标名清单、**两端格式表漂移**。
- 	ests/kotlin_syntax_check.py：用 kotlinc 对全部 Kotlin 源文件做语法级编译（没有 Android SDK 也能拦住括号/结构错误）。

## 六、常见问题

- **提示 `ffmpeg-kit` 找不到**：见上表 A/B/C；确认 `settings.gradle.kts` 里有 `mavenCentral()`。
- **APK 装到手机后转换报「ffmpeg 失败」**：确认 `abiFilters` 包含手机架构（真机多为 `arm64-v8a`）；模拟器 `x86_64`。
- **界面配色和截图不一样**：Android 12+ 默认跟随壁纸取色（MD3E 的 Material You）。
  想固定成品牌蓝，把 `MainActivity` 里的 `TransMusicTheme { … }` 改成 `TransMusicTheme(useDynamicColor = false) { … }`。
- **转换成功但文件不在音乐 App 里**：Android 11+ 媒体库有缓存，下拉刷新或重启播放器。
- **M4A 目标转出来是 AAC 还是 ALAC**：列表里 `AAC / M4A` 与 `ALAC / M4A` 是两项，按需选。
- **WMA/APE 源文件系统播放器放不了**：系统解码器不支持但 ffmpeg 支持，转换照常。
