using Microsoft.UI.Xaml;

namespace TransMusic.App;

public partial class App : Application
{
    private Window? _window;

    /// <summary>命令行里带的文件/文件夹（支持「发送到」「拖到 exe 上」直接打开）。</summary>
    public static IReadOnlyList<string> StartupPaths { get; private set; } = Array.Empty<string>();

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        StartupPaths = ParseStartupPaths(Environment.GetCommandLineArgs());
        _window = new MainWindow();
        _window.Activate();
    }

    private static IReadOnlyList<string> ParseStartupPaths(string[] commandLine)
    {
        // 第一个参数是 exe 自身；其余按文件/文件夹处理
        var paths = commandLine.Skip(1)
            .Where(a => !a.StartsWith('-') && (File.Exists(a) || Directory.Exists(a)))
            .ToArray();
        return paths;
    }
}
