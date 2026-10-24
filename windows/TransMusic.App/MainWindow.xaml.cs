using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using TransMusic.App.ViewModels;
using TransMusic.Core;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace TransMusic.App;

public sealed partial class MainWindow : Window
{
    public ConvertViewModel ViewModel { get; }

    public MainWindow()
    {
        ViewModel = new ConvertViewModel(DispatcherQueue);
        InitializeComponent();
        RootGrid.DataContext = ViewModel;

        Title = "音频格式转换器 · TransMusic";
        ConfigureWindow();

        ViewModel.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ConvertViewModel.IsRunning)) UpdateRunningState();
        };

        JobList.SelectionChanged += (_, _) => { };

        // 命令行/「发送到」打开的文件：启动后自动入列
        if (App.StartupPaths.Count > 0)
        {
            _ = ViewModel.AddPathsAsync(App.StartupPaths);
        }
        ApplyConflictSelection();
        UpdateEmptyHint();
        ViewModel.Jobs.CollectionChanged += (_, _) =>
        {
            UpdateEmptyHint();
            JobList.SelectedIndex = -1;
        };
    }

    private void ConfigureWindow()
    {
        try
        {
            ExtendsContentIntoTitleBar = true;
            SetTitleBar(AppTitleBar);
            var appWindow = AppWindow;
            appWindow.Resize(new SizeInt32(1180, 820));
            appWindow.TitleBar.PreferredHeightOption = Microsoft.UI.Windowing.TitleBarHeightOption.Standard;
        }
        catch
        {
            // 某些精简系统上 AppWindow 不可用，忽略即可
        }

        // 界面固定深色，用实心深色背景，不跟随系统 Mica，避免浅色系统下割裂。
    }

    // ------------------------------------------------------------------ 状态
    private void UpdateRunningState()
    {
        var running = ViewModel.IsRunning;
        StartButton.IsEnabled = !running;
        CancelButton.IsEnabled = running;
        FormatBox.IsEnabled = !running;
        QualityBox.IsEnabled = !running;
        SampleRateBox.IsEnabled = !running;
        ChannelBox.IsEnabled = !running;
        CompBox.IsEnabled = !running;
        PresetBox.IsEnabled = !running;
    }

    private void UpdateEmptyHint()
        => EmptyHint.Visibility = ViewModel.Jobs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

    private void ApplyConflictSelection()
        => ConflictBox.SelectedIndex = ViewModel.Conflict switch
        {
            "overwrite" => 1,
            "skip" => 2,
            _ => 0,
        };

    private string CurrentConflict => ConflictBox.SelectedIndex switch
    {
        1 => "overwrite",
        2 => "skip",
        _ => "rename",
    };

    // ------------------------------------------------------------------ 添加文件
    private void InitializePicker(object picker)
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
    }

    private async void OnAddFiles(object sender, RoutedEventArgs e)
    {
        try
        {
            var picker = new FileOpenPicker { ViewMode = PickerViewMode.List, SuggestedStartLocation = PickerLocationId.MusicLibrary };
            InitializePicker(picker);
            foreach (var ext in new[]
                     {
                         ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".aif", ".aiff",
                         ".wv", ".tta", ".ac3", ".eac3", ".mp2", ".spx", ".ape", ".mp4", ".mkv", ".mov",
                         ".flv", ".webm", ".m4v", ".3gp", ".m4b", ".caf", ".dsf", ".dff",
                     })
            {
                picker.FileTypeFilter.Add(ext);
            }
            var files = await picker.PickMultipleFilesAsync();
            if (files is { Count: > 0 }) await ViewModel.AddPathsAsync(files.Select(f => f.Path));
        }
        catch (Exception ex)
        {
            await ShowErrorAsync("无法打开文件选择器", ex.Message);
        }
    }

    private async void OnAddFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.MusicLibrary };
            picker.FileTypeFilter.Add("*");
            InitializePicker(picker);
            var folder = await picker.PickSingleFolderAsync();
            if (folder is not null) await ViewModel.AddPathsAsync(new[] { folder.Path });
        }
        catch (Exception ex)
        {
            await ShowErrorAsync("无法打开文件夹选择器", ex.Message);
        }
    }

    private void OnRemoveSelected(object sender, RoutedEventArgs e)
    {
        var selected = JobList.SelectedItems.OfType<Job>().ToList();
        ViewModel.RemoveSelected(selected);
        UpdateEmptyHint();
    }

    private void OnClear(object sender, RoutedEventArgs e)
    {
        ViewModel.Clear();
        UpdateEmptyHint();
    }

    private void OnToggleLog(object sender, RoutedEventArgs e)
        => ViewModel.LogVisible = !ViewModel.LogVisible;

    // ------------------------------------------------------------------ 输出目录
    private async void OnBrowseOutput(object sender, RoutedEventArgs e)
    {
        try
        {
            var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.MusicLibrary };
            picker.FileTypeFilter.Add("*");
            InitializePicker(picker);
            var folder = await picker.PickSingleFolderAsync();
            if (folder is null) return;
            ViewModel.SameAsSource = false;
            ViewModel.OutputDir = folder.Path;
        }
        catch (Exception ex)
        {
            await ShowErrorAsync("无法选择输出文件夹", ex.Message);
        }
    }

    // ------------------------------------------------------------------ 预设 / 运行
    private void OnPresetChanged(object sender, SelectionChangedEventArgs e)
    {
        if (PresetBox.SelectedItem is Preset preset)
        {
            ViewModel.ApplyPreset(preset);
            PresetBox.SelectedIndex = -1;
            UpdateRunningState();
        }
    }

    private void OnStart(object sender, RoutedEventArgs e)
    {
        ViewModel.Conflict = CurrentConflict;
        ViewModel.Start();
        UpdateRunningState();
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        ViewModel.Cancel();
        UpdateRunningState();
    }

    // ------------------------------------------------------------------ 拖放
    private void OnDragOver(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = "添加到转换列表";
        e.DragUIOverride.IsCaptionVisible = true;
    }

    private async void OnDrop(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        try
        {
            var items = await e.DataView.GetStorageItemsAsync();
            var paths = items.Select(i => i.Path).Where(p => !string.IsNullOrWhiteSpace(p));
            await ViewModel.AddPathsAsync(paths);
        }
        catch (Exception ex)
        {
            await ShowErrorAsync("拖放失败", ex.Message);
        }
    }

    // ------------------------------------------------------------------ 详情
    private async void OnJobDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (JobList.SelectedItem is not Job job) return;
        var info = job.Info;
        var text = new StringBuilder()
            .AppendLine($"编码：{(string.IsNullOrWhiteSpace(info.Codec) ? "-" : info.Codec)}")
            .AppendLine($"时长：{info.DurationText}    码率：{info.BitrateText}")
            .AppendLine($"采样率：{info.SampleRateText}    声道：{info.ChannelsText}")
            .AppendLine($"内嵌封面：{(info.HasCover ? "有" : "无")}")
            .AppendLine($"目标格式：{ViewModel.CurrentFormat.Label}")
            .AppendLine($"输出：{(string.IsNullOrWhiteSpace(job.OutputPath) ? "-" : job.OutputPath)}")
            .AppendLine($"状态：{job.StatusText}");
        if (job.Elapsed > TimeSpan.Zero) text.AppendLine($"耗时：{job.Elapsed.TotalSeconds:0.0} 秒");

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = job.FileName,
            Content = new TextBlock { Text = text.ToString(), TextWrapping = TextWrapping.Wrap, FontFamily = new FontFamily("Consolas") },
            CloseButtonText = "关闭",
        };
        await dialog.ShowAsync();
    }

    private async Task ShowErrorAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = title,
            Content = message,
            CloseButtonText = "知道了",
        };
        await dialog.ShowAsync();
    }
}
