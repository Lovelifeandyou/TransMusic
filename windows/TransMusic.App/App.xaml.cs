using System.Diagnostics;
using Microsoft.UI.Xaml;

namespace TransMusic.App;

public partial class App : Application
{
    private Window? _window;

    /// <summary>命令行里带的文件/文件夹（支持「发送到」「拖到 exe 上」直接打开）。</summary>
    public static IReadOnlyList<string> StartupPaths { get; private set; } = Array.Empty<string>();

    public App()
    {
        // 应用级主题必须在 InitializeComponent 之前设定；运行中改会导致半套主题，
        // 所以切换主题时用 RestartWithCurrentTheme 重启进程（WinUI 的通行做法）。
        var mode = Core.AppSettings.Load().ThemeMode;
        if (mode == 1) RequestedTheme = ApplicationTheme.Light;
        else if (mode == 2) RequestedTheme = ApplicationTheme.Dark;
        // mode == 0：不设置，跟随系统

        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        StartupPaths = ParseStartupPaths(Environment.GetCommandLineArgs());
        _window = new MainWindow();
        _window.Activate();
    }

    /// <summary>重启自身，让新主题生效。</summary>
    public static void RestartWithCurrentTheme()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exe))
            {
                Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
            }
        }
        catch
        {
            // 启动失败就只退出，用户手动再开
        }
        Current.Exit();
    }

    private static IReadOnlyList<string> ParseStartupPaths(string[] commandLine)
    {
        return commandLine.Skip(1)
            .Where(a => !a.StartsWith('-') && (File.Exists(a) || Directory.Exists(a)))
            .ToArray();
    }
}
