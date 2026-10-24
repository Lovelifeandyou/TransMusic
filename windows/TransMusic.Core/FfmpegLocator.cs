namespace TransMusic.Core;

/// <summary>找不到 ffmpeg 时抛出（界面上会提示放置位置）。</summary>
public sealed class FfmpegNotFoundException : Exception
{
    public FfmpegNotFoundException(string message) : base(message) { }
}

/// <summary>
/// 定位 ffmpeg 可执行文件：环境变量 → 程序目录 → tools 目录 → PATH。
/// </summary>
public static class FfmpegLocator
{
    private static string? _cached;

    public static string Find(bool force = false)
    {
        if (!force && _cached is not null && File.Exists(_cached)) return _cached;

        var exeName = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";

        var env = Environment.GetEnvironmentVariable("TRANSMUSIC_FFMPEG");
        if (!string.IsNullOrEmpty(env) && File.Exists(env)) return _cached = env;

        var roots = new List<string> { AppContext.BaseDirectory, Directory.GetCurrentDirectory() };
        var subs = new[]
        {
            string.Empty,
            "ffmpeg",
            "tools",
            Path.Combine("tools", "ffmpeg"),
            Path.Combine("tools", "ffmpeg", "bin"),
            Path.Combine("ffmpeg", "bin"),
        };
        foreach (var root in roots)
        {
            foreach (var sub in subs)
            {
                var candidate = Path.Combine(root, sub, exeName);
                if (File.Exists(candidate)) return _cached = candidate;
            }
        }

        var pathVar = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var dir in pathVar.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(dir.Trim(), exeName);
                if (File.Exists(candidate)) return _cached = candidate;
            }
            catch
            {
                // 忽略非法 PATH 项
            }
        }

        throw new FfmpegNotFoundException(
            "未找到 ffmpeg。请把 ffmpeg.exe 放到程序目录或 tools\\ 目录下，" +
            "或设置环境变量 TRANSMUSIC_FFMPEG 指向它。");
    }

    public static string Version()
    {
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(Find())
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
            };
            psi.ArgumentList.Add("-hide_banner");
            psi.ArgumentList.Add("-version");
            using var proc = System.Diagnostics.Process.Start(psi);
            if (proc is null) return "unknown";
            var first = proc.StandardOutput.ReadLine() ?? "unknown";
            proc.WaitForExit(5000);
            return first;
        }
        catch (Exception ex)
        {
            return $"不可用（{ex.Message}）";
        }
    }
}
