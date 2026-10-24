using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace TransMusic.Core;

/// <summary>
/// 封面缓存：用 ffmpeg 把音频里的内嵌封面（ID3 APIC / FLAC PICTURE / MP4 covr）导出成 png，
/// 存到 %LOCALAPPDATA%\TransMusic\covers\&lt;hash&gt;.png，界面直接绑定这个文件。
/// </summary>
public static class CoverCache
{
    public static string CacheDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TransMusic",
        "covers");

    /// <summary>提取封面；没有封面或失败时返回 null。</summary>
    public static string? TryExtract(string sourcePath)
    {
        if (!File.Exists(sourcePath)) return null;

        var hash = Convert.ToHexString(
                MD5.HashData(Encoding.UTF8.GetBytes(sourcePath.ToLowerInvariant())))
            .ToLowerInvariant();
        var target = Path.Combine(CacheDirectory, hash + ".png");
        if (File.Exists(target) && new FileInfo(target).Length > 0) return target;
        if (!Directory.Exists(CacheDirectory)) Directory.CreateDirectory(CacheDirectory);

        string ffmpeg;
        try
        {
            ffmpeg = FfmpegLocator.Find();
        }
        catch
        {
            return null;
        }

        var psi = new ProcessStartInfo(ffmpeg)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in new[]
                 {
                     "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
                     "-i", sourcePath,
                     "-map", "0:v:0", "-frames:v", "1", "-c:v", "png",
                     target,
                 })
        {
            psi.ArgumentList.Add(arg);
        }

        try
        {
            using var proc = Process.Start(psi);
            if (proc is null) return null;
            proc.StandardOutput.ReadToEnd();
            proc.StandardError.ReadToEnd();
            proc.WaitForExit(20000);
        }
        catch
        {
            return null;
        }

        return File.Exists(target) && new FileInfo(target).Length > 0 ? target : null;
    }

    /// <summary>清理缓存目录（界面「清空列表」时调用）。</summary>
    public static void Clear()
    {
        try
        {
            if (Directory.Exists(CacheDirectory)) Directory.Delete(CacheDirectory, true);
        }
        catch
        {
            // 文件被占用时忽略
        }
    }
}
