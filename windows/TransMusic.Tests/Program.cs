using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using TransMusic.Core;

namespace TransMusic.Tests;

/// <summary>
/// WinUI 引擎（TransMusic.Core）的自动化自检：
///
///   TransMusic.Tests selftest --ffmpeg &lt;path&gt; [--work &lt;dir&gt;]
///       真正生成音频、真转一遍、校验输出（含取消、命名冲突、模板）
///
///   TransMusic.Tests parity &lt;cases.json&gt; &lt;out.json&gt;
///       按用例输出 ffmpeg 参数列表，供 Python 端逐条对比
/// </summary>
public static class Program
{
    private static int _passed;
    private static int _failed;
    private static readonly List<string> Failures = new();

    public static int Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        if (args.Length == 0)
        {
            Console.WriteLine("用法：TransMusic.Tests selftest --ffmpeg <path> | parity <cases.json> <out.json>");
            return 2;
        }

        return args[0] switch
        {
            "selftest" => RunSelfTest(Option(args, "--ffmpeg"), Option(args, "--work")),
            "parity" => RunParity(args.ElementAtOrDefault(1), args.ElementAtOrDefault(2)),
            _ => 2,
        };
    }

    private static string? Option(string[] args, string name)
    {
        var index = Array.IndexOf(args, name);
        return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
    }

    // ------------------------------------------------------------------ 参数一致性
    private sealed class ParityCase
    {
        public string Id { get; set; } = string.Empty;
        public string Fmt { get; set; } = string.Empty;
        public int Bitrate { get; set; }
        public int Vbr { get; set; } = -1;
        public int Sr { get; set; }
        public int Ch { get; set; }
        public int Depth { get; set; }
        public int Comp { get; set; } = -1;
        public int MetaValue { get; set; } = 1;
        public int CoverValue { get; set; } = 1;
        public int SrcSr { get; set; }
        public int SrcCh { get; set; }
        public int SrcCover { get; set; }
        public string SrcCodec { get; set; } = string.Empty;
    }

    private static int RunParity(string? casesPath, string? outPath)
    {
        if (casesPath is null || outPath is null)
        {
            Console.Error.WriteLine("parity 需要 <cases.json> <out.json>");
            return 2;
        }

        var json = File.ReadAllText(casesPath);
        var readOptions = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
        var cases = JsonSerializer.Deserialize<List<ParityCase>>(json, readOptions) ?? new List<ParityCase>();
        var result = new Dictionary<string, string[]>();

        foreach (var c in cases)
        {
            var fmt = Formats.Find(c.Fmt);
            if (fmt is null)
            {
                result[c.Id] = new[] { "ERROR_UNKNOWN_FORMAT" };
                continue;
            }
            var settings = new ConvertSettings
            {
                FormatKey = fmt.Key,
                Bitrate = c.Bitrate,
                VbrIndex = c.Vbr,
                SampleRate = c.Sr,
                Channels = c.Ch,
                BitDepth = c.Depth,
                CompLevel = c.Comp,
                KeepMetadata = c.MetaValue == 1,
                KeepCover = c.CoverValue == 1,
            };
            var info = new MediaInfo { SampleRate = c.SrcSr, Channels = c.SrcCh, HasCover = c.SrcCover == 1, Codec = c.SrcCodec };
            var args = CommandBuilder.Build(
                "D:/音乐/in 1.mp3", "D:/out/out 1.flac", fmt, settings, info, reportProgress: false);
            result[c.Id] = args.ToArray();
        }

        File.WriteAllText(outPath, JsonSerializer.Serialize(result, new JsonSerializerOptions
        {
            WriteIndented = false,
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        }));
        Console.WriteLine($"已输出 {result.Count} 个用例参数 → {outPath}");
        return 0;
    }

    // ------------------------------------------------------------------ 真实转换自测
    private static int RunSelfTest(string? ffmpeg, string? workDir)
    {
        ffmpeg = ffmpeg ?? FindFfmpegNearby();
        if (ffmpeg is null || !File.Exists(ffmpeg))
        {
            Console.WriteLine("✘ 找不到 ffmpeg，请用 --ffmpeg <path> 指定");
            return 1;
        }
        Environment.SetEnvironmentVariable("TRANSMUSIC_FFMPEG", ffmpeg);

        var work = workDir ?? Path.Combine(Path.GetTempPath(), "tm-winui-selftest");
        if (Directory.Exists(work)) Directory.Delete(work, true);
        Directory.CreateDirectory(work);

        Console.WriteLine($"ffmpeg : {ffmpeg}");
        Console.WriteLine($"工作目录: {work}");
        Console.WriteLine($"引擎版本: {FfmpegLocator.Version().Split(" Copyright")[0]}");
        Console.WriteLine();

        CheckFormatsTable();
        var source = MakeSource(ffmpeg, work);
        if (source is null) return 1;
        CheckProbe(source);
        CheckCommandBuilder();
        CheckOutputResolver(work);
        ConvertMatrix(source, work);
        CheckDsd(work);
        CancelTest(ffmpeg, work);

        Console.WriteLine();
        Console.WriteLine(new string('=', 58));
        if (_failed > 0)
        {
            Console.WriteLine($"通过 {_passed}，失败 {_failed}");
            foreach (var f in Failures) Console.WriteLine("  ✘ " + f);
            return 1;
        }
        Console.WriteLine($"全部通过 ✔（{_passed} 项检查）");
        return 0;
    }

    private static string? FindFfmpegNearby()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe"),
            Path.Combine(AppContext.BaseDirectory, "ffmpeg", "ffmpeg.exe"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "TransMusic.App", "ffmpeg", "ffmpeg.exe")),
        };
        return candidates.FirstOrDefault(File.Exists);
    }

    private static void Check(bool ok, string name, string? detail = null)
    {
        if (ok)
        {
            _passed++;
            Console.WriteLine($"  ✔ {name}");
        }
        else
        {
            _failed++;
            Failures.Add(detail is null ? name : $"{name} —— {detail}");
            Console.WriteLine($"  ✘ {name}{(detail is null ? "" : " —— " + detail)}");
        }
    }

    private static void CheckFormatsTable()
    {
        Console.WriteLine("── 格式表");
        Check(Formats.Ordered.Count == 15, "15 种目标格式", $"实际 {Formats.Ordered.Count}");
        Check(Presets.All.Count == 11, "11 个预设（含 3 个 DSD 预设）", $"实际 {Presets.All.Count}");
        Check(Formats.Get("mp3").Codec == "libmp3lame", "mp3 编码器为 libmp3lame");
        Check(Formats.Get("flac").Lossless, "flac 标记为无损");
        Check(AudioExts.IsAudioLike("a.MP3") && AudioExts.IsAudioLike("b.flac") && !AudioExts.IsAudioLike("c.txt"),
            "扩展名过滤");
    }

    private static string? MakeSource(string ffmpeg, string work)
    {
        Console.WriteLine("── 生成测试音频");
        var wav = Path.Combine(work, "source.wav");
        var ok = RunProcess(ffmpeg, new[]
        {
            "-hide_banner", "-y", "-f", "lavfi",
            "-i", "sine=frequency=440:duration=3:sample_rate=44100",
            "-af", "aeval=val(0)|val(0)*0.7", "-ac", "2", "-c:a", "pcm_s16le", wav,
        });
        Check(ok && File.Exists(wav), "合成 3 秒 44.1kHz 立体声 wav");
        return File.Exists(wav) ? wav : null;
    }

    private static void CheckProbe(string source)
    {
        Console.WriteLine("── 媒体探测");
        var info = AudioProbe.Probe(source);
        Check(info.Ok, "探测成功");
        Check(Math.Abs(info.DurationSeconds - 3.0) < 0.2, "时长约 3 秒", $"实际 {info.DurationSeconds:0.00}");
        Check(info.SampleRate == 44100, "采样率 44100", $"实际 {info.SampleRate}");
        Check(info.Channels == 2, "立体声", $"实际 {info.Channels}");
        Check(info.Codec.Contains("pcm"), "编码 pcm", $"实际 {info.Codec}");
    }

    private static void CheckCommandBuilder()
    {
        Console.WriteLine("── 命令构造");
        var mp3 = Formats.Get("mp3");
        var args = CommandBuilder.Build(
            "in.wav", "out.mp3", mp3, new ConvertSettings { Bitrate = 320 }, new MediaInfo(), reportProgress: false);
        Check(args.SequenceEqual(new[]
        {
            "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", "in.wav",
            "-map", "0:a:0", "-vn", "-sn", "-dn", "-c:a", "libmp3lame", "-b:a", "320k",
            "-map_metadata", "0", "-map_chapters", "-1", "-id3v2_version", "3", "-write_id3v1", "1",
            "out.mp3",
        }), "MP3 320 CBR 参数顺序正确", string.Join(" ", args));

        var flac = Formats.Get("flac");
        var flacArgs = CommandBuilder.Build(
            "in.wav", "out.flac", flac,
            new ConvertSettings { BitDepth = 24, CompLevel = 8 }, new MediaInfo(), reportProgress: false);
        Check(flacArgs.Contains("-sample_fmt") && flacArgs.Contains("s32"), "FLAC 24bit → -sample_fmt s32");
        Check(flacArgs.Contains("-compression_level") && flacArgs.Contains("8"), "FLAC 压缩级别 8");

        var wav = Formats.Get("wav");
        var wavArgs = CommandBuilder.Build(
            "in.flac", "out.wav", wav, new ConvertSettings { BitDepth = 32 }, new MediaInfo(), reportProgress: false);
        Check(wavArgs.Contains("pcm_f32le"), "WAV 32bit → pcm_f32le 编码器");

        var opus = Formats.Get("opus");
        var opusArgs = CommandBuilder.Build(
            "in.wav", "out.opus", opus, new ConvertSettings { Bitrate = 128 }, new MediaInfo { SampleRate = 44100 },
            reportProgress: false);
        Check(opusArgs.Contains("-ar") && opusArgs.Contains("48000"), "Opus 强制 48kHz");
        Check(opusArgs.Contains("-application"), "Opus 带 -application audio");

        var spx = Formats.Get("spx");
        var spxArgs = CommandBuilder.Build(
            "in.wav", "out.spx", spx, new ConvertSettings { Bitrate = 16 }, new MediaInfo { SampleRate = 48000, Channels = 2 },
            reportProgress: false);
        Check(spxArgs.Contains("-ac") && spxArgs.Contains("1"), "Speex 强制单声道");

        var coverArgs = CommandBuilder.Build(
            "in.mp3", "out.m4a", Formats.Get("m4a_aac"), new ConvertSettings { Bitrate = 256 },
            new MediaInfo { HasCover = true }, reportProgress: false);
        Check(coverArgs.Contains("-map") && coverArgs.Contains("0:v:0?") && coverArgs.Contains("attached_pic"),
            "有封面时复制封面流");

        var noCoverArgs = CommandBuilder.Build(
            "in.mp3", "out.m4a", Formats.Get("m4a_aac"), new ConvertSettings { Bitrate = 256 },
            new MediaInfo { HasCover = false }, reportProgress: false);
        Check(noCoverArgs.Contains("-vn"), "无封面时 -vn");

        var keepCoverOffArgs = CommandBuilder.Build(
            "in.mp3", "out.m4a", Formats.Get("m4a_aac"), new ConvertSettings { Bitrate = 256, KeepCover = false },
            new MediaInfo { HasCover = true }, reportProgress: false);
        Check(keepCoverOffArgs.Contains("-vn"), "关闭封面复制时 -vn");
    }

    private static void CheckOutputResolver(string work)
    {
        Console.WriteLine("── 输出路径");
        var fmt = Formats.Get("flac");
        var info = new MediaInfo { Artist = "夜航乐队", Title = "夜航船" };

        var resolved = OutputPathResolver.Resolve(
            Path.Combine("D:/music", "song.mp3"), work, fmt, info, template: "{artist} - {title}", index: 3);
        Check(resolved is not null && Path.GetFileName(resolved) == "夜航乐队 - 夜航船.flac",
            "模板 {artist} - {title}", Path.GetFileName(resolved ?? ""));

        var withIndex = OutputPathResolver.Resolve(
            Path.Combine("D:/music", "song.mp3"), work, fmt, info, template: "{index} {name}", index: 3);
        Check(Path.GetFileName(withIndex ?? "") == "03 song.flac", "模板 {index} 补零", Path.GetFileName(withIndex ?? ""));

        var existing = Path.Combine(work, "dup.flac");
        File.WriteAllText(existing, "x");
        var skipped = OutputPathResolver.Resolve(
            Path.Combine(work, "dup.mp3"), work, fmt, new MediaInfo(), template: "dup", conflict: "skip");
        Check(skipped is null, "冲突策略 skip 返回 null");

        var renamed = OutputPathResolver.Resolve(
            Path.Combine(work, "dup.mp3"), work, fmt, new MediaInfo(), template: "dup", conflict: "rename");
        Check(Path.GetFileName(renamed ?? "") == "dup (1).flac", "冲突策略 rename 追加序号", Path.GetFileName(renamed ?? ""));

        var overwrite = OutputPathResolver.Resolve(
            Path.Combine(work, "dup.mp3"), work, fmt, new MediaInfo(), template: "dup", conflict: "overwrite");
        Check(overwrite == existing, "冲突策略 overwrite 保留原名");

        var selfTarget = OutputPathResolver.Resolve(
            Path.Combine(work, "dup.flac"), work, fmt, new MediaInfo(), template: "dup", conflict: "overwrite");
        Check(selfTarget is not null && !string.Equals(selfTarget, Path.Combine(work, "dup.flac"), StringComparison.OrdinalIgnoreCase),
            "目标等于源文件时自动改名");
    }

    private static void ConvertMatrix(string source, string work)
    {
        Console.WriteLine("── 真实转换（15 种格式）");
        var info = AudioProbe.Probe(source);
        var outDir = Path.Combine(work, "out");
        Directory.CreateDirectory(outDir);
        var failures = new List<string>();

        foreach (var fmt in Formats.Ordered)
        {
            var settings = new ConvertSettings
            {
                FormatKey = fmt.Key,
                Bitrate = fmt.DefaultBitrate,
                VbrIndex = -1,
                BitDepth = fmt.DefaultBitDepth,
                CompLevel = fmt.CompLevels is null ? -1 : fmt.DefaultComp(),
            };
            var dst = Path.Combine(outDir, $"conv.{fmt.Ext}");
            var args = CommandBuilder.Build(source, dst, fmt, settings, info);
            var result = FfmpegRunner.RunAsync(args, info.DurationSeconds).GetAwaiter().GetResult();
            if (!result.Ok)
            {
                failures.Add($"{fmt.Key}: {result.Message}");
                continue;
            }
            var probed = AudioProbe.Probe(dst);
            var size = new FileInfo(dst).Length;
            if (size < 512 || !probed.Ok || Math.Abs(probed.DurationSeconds - info.DurationSeconds) > 0.3)
            {
                failures.Add($"{fmt.Key}: 输出异常 size={size} dur={probed.DurationSeconds:0.00} ok={probed.Ok}");
            }
        }

        Check(failures.Count == 0, "15 种格式全部转换成功且可回读",
            failures.Count == 0 ? null : string.Join("; ", failures));
    }

    /// <summary>DSD 输入：.dsf 应被识别并降采样到 88.2k 系 PCM。</summary>
    private static void CheckDsd(string work)
    {
        Console.WriteLine("── DSD 输入");
        string? dsd = null;
        foreach (var candidate in new[]
                 {
                     Path.Combine(Directory.GetCurrentDirectory(), "samples", "dsd64_test.dsf"),
                     Path.Combine(Directory.GetCurrentDirectory(), "..", "samples", "dsd64_test.dsf"),
                     Path.Combine(work, "dsd64_test.dsf"),
                 })
        {
            if (File.Exists(candidate)) { dsd = Path.GetFullPath(candidate); break; }
        }
        if (dsd is null)
        {
            Console.WriteLine("  – 跳过（没有 samples/dsd64_test.dsf，可运行 tools/make_dsd_sample.py 生成）");
            return;
        }

        var info = AudioProbe.Probe(dsd);
        Check(DsdSupport.IsDsdSource(info.Codec, info.SampleRate, dsd), "DSD 源识别",
            $"{info.Codec} {info.SampleRate}Hz");

        var outDir = Path.Combine(work, "dsd");
        Directory.CreateDirectory(outDir);
        var cases = new[] { ("flac", 24, 88200), ("wav", 24, 88200), ("mp3", 0, 48000) };
        foreach (var (key, depth, expectRate) in cases)
        {
            var fmt = Formats.Get(key);
            var settings = new ConvertSettings
            {
                FormatKey = key,
                Bitrate = 320,
                BitDepth = depth,
                CompLevel = fmt.CompLevels is null ? -1 : fmt.DefaultComp(),
            };
            var dst = Path.Combine(outDir, $"dsdout.{fmt.Ext}");
            var args = CommandBuilder.Build(dsd, dst, fmt, settings, info);
            var result = FfmpegRunner.RunAsync(args, info.DurationSeconds).GetAwaiter().GetResult();
            if (!result.Ok)
            {
                Check(false, $"DSD → {key}", result.Message);
                continue;
            }
            var probed = AudioProbe.Probe(dst);
            Check(probed.SampleRate == expectRate, $"DSD → {key} 采样率 {expectRate}",
                $"实际 {probed.SampleRate}");
        }
    }

    private static void CancelTest(string ffmpeg, string work)
    {
        Console.WriteLine("── 队列与取消");
        var longWav = Path.Combine(work, "long.wav");
        RunProcess(ffmpeg, new[]
        {
            "-hide_banner", "-y", "-f", "lavfi", "-i", "sine=frequency=220:duration=300:sample_rate=44100",
            "-ac", "2", "-c:a", "pcm_s16le", longWav,
        });
        if (!File.Exists(longWav))
        {
            Check(false, "生成长音频");
            return;
        }

        // 先跑一个短任务，验证队列能正常报完成
        var shortWav = Path.Combine(work, "source.wav");
        var shortJob = new Job { Id = 1, SourcePath = shortWav, Info = AudioProbe.Probe(shortWav) };
        var completed = new TaskCompletionSource<QueueStats>();
        var queue = new ConvertQueue();
        queue.Finished += stats => completed.TrySetResult(stats);
        queue.Start(new[] { shortJob }, Formats.Get("ogg"), new ConvertSettings { Bitrate = 128 }, work, "{name}", "rename", 2);
        var finished = completed.Task.Wait(TimeSpan.FromSeconds(60)) ? completed.Task.Result : null;
        Check(shortJob.Status == JobStatus.Done, "队列任务正常完成", shortJob.Message);
        Check(finished is { Ok: 1, Failed: 0 }, "队列统计正确", finished is null ? "无统计" : $"ok={finished.Ok} failed={finished.Failed}");

        // 再跑长任务并中途取消
        var job = new Job { Id = 2, SourcePath = longWav, Info = AudioProbe.Probe(longWav) };
        var done = new TaskCompletionSource<QueueStats>();
        var cancelling = new ConvertQueue();
        cancelling.Finished += stats => done.TrySetResult(stats);
        cancelling.Start(new[] { job }, Formats.Get("flac"),
            new ConvertSettings { BitDepth = 16, CompLevel = 12 }, work, "{name}", "rename", 1);

        Thread.Sleep(900);
        cancelling.Cancel();
        var stats2 = done.Task.Wait(TimeSpan.FromSeconds(30)) ? done.Task.Result : null;
        var partial = Path.Combine(work, "long.flac");

        Check(job.Status == JobStatus.Canceled, "任务被标记为已取消", job.Status.ToString());
        Check(stats2 is { Canceled: 1 }, "取消计入统计", stats2 is null ? "无统计" : $"canceled={stats2.Canceled}");
        Check(!File.Exists(partial), "取消后没有残留半成品");
        Check(!cancelling.IsRunning, "队列已停止");
    }

    private static bool RunProcess(string exe, string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(exe)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var proc = Process.Start(psi);
            if (proc is null) return false;
            proc.StandardOutput.ReadToEnd();
            proc.StandardError.ReadToEnd();
            proc.WaitForExit(120000);
            return proc.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }
}
