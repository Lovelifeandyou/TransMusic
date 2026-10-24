using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;

namespace TransMusic.Launcher;

/// <summary>
/// 单文件启动器。
///
/// WinUI 3 官方不支持 PublishSingleFile（Windows App SDK 的 Bootstrap 与 MRT Core
/// 都按「exe 所在目录」查找原生库和 resources.pri，而单文件把库解压到临时目录，
/// 会直接抛 .NET 未处理异常 0xe0434352）。
///
/// 所以这里换一种做法：把完整应用（含 ffmpeg）作为 zip 内嵌进一个普通 exe，
/// 首次运行时解压到 %LOCALAPPDATA%\TransMusic\app\&lt;版本&gt;，再启动真正的 TransMusic.exe。
/// 对外仍然只有一个 exe 文件。
/// </summary>
internal static class Program
{
    private const string AppFolderName = "TransMusic";
    private const string PayloadResource = "payload.zip";

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);

    private const uint MB_ICONERROR = 0x10;
    private const uint MB_ICONINFORMATION = 0x40;

    private static int Main(string[] args)
    {
        try
        {
            var version = PayloadVersion();
            var baseDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                AppFolderName, "app");
            var appDir = Path.Combine(baseDir, version);
            var exePath = Path.Combine(appDir, "TransMusic.exe");

            if (!File.Exists(exePath))
            {
                ExtractPayload(appDir);
            }

            CleanOldVersions(baseDir, version);

            var startInfo = new ProcessStartInfo(exePath)
            {
                UseShellExecute = false,
                WorkingDirectory = appDir,
            };
            foreach (var arg in args) startInfo.ArgumentList.Add(arg);

            var proc = Process.Start(startInfo);
            // 应用已交给系统，启动器立刻退出（不占进程、不占内存）
            return proc is null ? 1 : 0;
        }
        catch (Exception ex)
        {
            MessageBoxW(IntPtr.Zero,
                $"启动失败：\n\n{ex.Message}\n\n可尝试删除 %LOCALAPPDATA%\\TransMusic\\app 后重新运行。",
                "音频格式转换器", MB_ICONERROR);
            return 1;
        }
    }

    /// <summary>用内嵌 payload 的大小 + 程序版本做目录名，升级后自然换目录。</summary>
    private static string PayloadVersion()
    {
        var asm = Assembly.GetExecutingAssembly();
        var info = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
                   ?? asm.GetName().Version?.ToString()
                   ?? "1.0.0";
        var plus = info.IndexOf('+');
        if (plus > 0) info = info[..plus];
        var safe = new string(info.Select(c => char.IsLetterOrDigit(c) || c == '.' ? c : '_').ToArray());
        return safe;
    }

    private static void ExtractPayload(string appDir)
    {
        var asm = Assembly.GetExecutingAssembly();
        using var stream = asm.GetManifestResourceStream(PayloadResource)
                           ?? throw new InvalidOperationException("安装包损坏：缺少内置的应用数据。");

        Directory.CreateDirectory(appDir);

        // 先解压到临时目录，完整解压后再改名，避免中途失败留下半套文件
        var staging = appDir + ".tmp";
        if (Directory.Exists(staging)) Directory.Delete(staging, true);
        Directory.CreateDirectory(staging);

        using (var archive = new ZipArchive(stream, ZipArchiveMode.Read))
        {
            archive.ExtractToDirectory(staging, overwriteFiles: true);
        }

        if (Directory.Exists(appDir)) Directory.Delete(appDir, true);
        Directory.Move(staging, appDir);
    }

    private static void CleanOldVersions(string baseDir, string keep)
    {
        try
        {
            foreach (var dir in Directory.GetDirectories(baseDir))
            {
                if (string.Equals(Path.GetFileName(dir), keep, StringComparison.OrdinalIgnoreCase)) continue;
                try { Directory.Delete(dir, true); } catch { /* 旧版本正在运行就跳过 */ }
            }
            foreach (var dir in Directory.GetDirectories(baseDir, "*.tmp"))
            {
                try { Directory.Delete(dir, true); } catch { }
            }
        }
        catch
        {
            // 清理失败不影响启动
        }
    }
}
