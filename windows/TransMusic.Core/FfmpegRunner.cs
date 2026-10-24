using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace TransMusic.Core;

/// <summary>一次 ffmpeg 执行的结果。</summary>
public sealed record ConvertResult(bool Ok, bool Canceled, string Message, TimeSpan Elapsed);

/// <summary>
/// 执行 ffmpeg：通过 <c>-progress pipe:1</c> 实时解析进度，支持取消（连子进程一起结束）。
/// </summary>
public static class FfmpegRunner
{
    private sealed class ProgressParser
    {
        private double _seconds;
        private double _speed;

        public void Feed(string line, double duration, Action<double, string>? onProgress)
        {
            var idx = line.IndexOf('=');
            if (idx <= 0) return;
            var key = line[..idx].Trim();
            var value = line[(idx + 1)..].Trim();

            switch (key)
            {
                case "out_time":
                    _seconds = ParseTime(value);
                    break;
                case "speed":
                    _speed = ParseSpeed(value);
                    break;
                case "progress":
                    if (duration > 0 && _seconds > 0)
                    {
                        var pct = Math.Min(99.5, _seconds / duration * 100.0);
                        onProgress?.Invoke(pct, _speed > 0 ? $"{_speed:0.0}x" : string.Empty);
                    }
                    break;
            }
        }

        private static double ParseTime(string text)
        {
            var parts = text.Split(':');
            if (parts.Length != 3) return 0;
            if (!int.TryParse(parts[0], out var h)) return 0;
            if (!int.TryParse(parts[1], out var m)) return 0;
            if (!double.TryParse(parts[2], System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out var s)) return 0;
            return h * 3600 + m * 60 + s;
        }

        private static double ParseSpeed(string text)
        {
            var t = text.TrimEnd('x', ' ');
            return double.TryParse(t, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : 0;
        }
    }

    public static Task<ConvertResult> RunAsync(
        IReadOnlyList<string> args,
        double durationSeconds,
        Action<double, string>? onProgress = null,
        CancellationToken cancellationToken = default)
        => RunAsync(FfmpegLocator.Find(), args, durationSeconds, onProgress, cancellationToken);

    public static async Task<ConvertResult> RunAsync(
        string ffmpegPath,
        IReadOnlyList<string> args,
        double durationSeconds,
        Action<double, string>? onProgress = null,
        CancellationToken cancellationToken = default)
    {
        var psi = new ProcessStartInfo(ffmpegPath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var arg in args) psi.ArgumentList.Add(arg);

        var stderr = new StringBuilder();
        var parser = new ProgressParser();
        var sw = Stopwatch.StartNew();

        using var proc = new Process { StartInfo = psi };
        proc.OutputDataReceived += (_, e) =>
        {
            if (e.Data is null) return;
            try { parser.Feed(e.Data, durationSeconds, onProgress); } catch { /* 进度解析失败不影响转换 */ }
        };
        proc.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is null) return;
            lock (stderr) stderr.AppendLine(e.Data);
        };

        try
        {
            proc.Start();
        }
        catch (Exception ex)
        {
            return new ConvertResult(false, false, $"无法启动 ffmpeg：{ex.Message}", sw.Elapsed);
        }

        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();

        await using var registration = cancellationToken.Register(() =>
        {
            try
            {
                if (!proc.HasExited) proc.Kill(entireProcessTree: true);
            }
            catch
            {
                // 进程可能刚好自行退出
            }
        });

        await proc.WaitForExitAsync(CancellationToken.None).ConfigureAwait(false);
        // 等待 stdout/stderr 读完（异步读取的事件回调可能还在队列里）
        try { proc.WaitForExit(); } catch { }

        var elapsed = sw.Elapsed;

        if (cancellationToken.IsCancellationRequested)
        {
            return new ConvertResult(false, true, "已取消", elapsed);
        }

        if (proc.ExitCode != 0)
        {
            string message;
            lock (stderr)
            {
                var lines = stderr.ToString()
                    .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                message = lines.Length == 0
                    ? $"ffmpeg 退出码 {proc.ExitCode}"
                    : string.Join(" ", lines.TakeLast(3));
            }
            return new ConvertResult(false, false, message, elapsed);
        }

        onProgress?.Invoke(100.0, "完成");
        return new ConvertResult(true, false, "完成", elapsed);
    }
}
