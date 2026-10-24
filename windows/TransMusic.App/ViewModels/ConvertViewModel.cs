using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text;
using Microsoft.UI.Dispatching;
using TransMusic.Core;

namespace TransMusic.App.ViewModels;

/// <summary>
/// 界面状态：任务列表、参数选择、总体进度、日志。
/// 转码逻辑全在 TransMusic.Core，这里只做状态与线程编组。
/// </summary>
public sealed class ConvertViewModel : INotifyPropertyChanged
{
    private readonly DispatcherQueue _dispatcher;
    private readonly ConvertQueue _queue;
    private readonly AppSettings _settings;
    private readonly StringBuilder _logBuilder = new();
    private readonly List<QualityItem> _qualityItems = new();
    private List<int> _sampleRates = new();
    private List<int> _channelList = new();
    private int _nextJobId;

    public ObservableCollection<Job> Jobs { get; } = new();

    public ConvertViewModel(DispatcherQueue dispatcher)
    {
        _dispatcher = dispatcher;
        // 队列负责把 Job 的状态写入编组到 UI 线程，避免绑定跨线程异常
        _queue = new ConvertQueue(action => _dispatcher.TryEnqueue(() => action()));
        _settings = AppSettings.Load();

        FormatIndex = Math.Max(0, Core.Formats.Ordered.ToList().FindIndex(f => f.Key == _settings.FormatKey));
        ThemeModeIndex = Math.Clamp(_settings.ThemeMode, 0, 2);
        KeepMetadata = _settings.KeepMetadata;
        KeepCover = _settings.KeepCover;
        Workers = Math.Clamp(_settings.Workers, 1, 8);
        OutputDir = _settings.OutputDir;
        SameAsSource = _settings.SameAsSource;
        Template = string.IsNullOrWhiteSpace(_settings.Template) ? "{name}" : _settings.Template;
        Conflict = string.IsNullOrWhiteSpace(_settings.Conflict) ? "rename" : _settings.Conflict;

        RebuildFormatOptions(restoreSaved: true);

        _queue.JobUpdated += OnJobUpdated;            // 已在 UI 线程（见队列的 marshal）
        _queue.JobProgress += (_, _, _) => RecomputeOverall();
        _queue.Log += line => _dispatcher.TryEnqueue(() => AppendLog(line));
        _queue.Finished += stats => _dispatcher.TryEnqueue(() => OnFinished(stats));

        Task.Run(() =>
        {
            var raw = Core.FfmpegLocator.Version();
            // 形如 "ffmpeg version 7.1-essentials_build ... Copyright ..." → "ffmpeg 7.1"
            var shortVersion = "ffmpeg";
            var match = System.Text.RegularExpressions.Regex.Match(raw, @"version\s+([0-9]+(?:\.[0-9]+)?)");
            if (match.Success) shortVersion = "ffmpeg " + match.Groups[1].Value;
            else if (raw.Length <= 24) shortVersion = raw;
            _dispatcher.TryEnqueue(() => EngineVersion = shortVersion);
        });
    }

    // ------------------------------------------------------------------ 参数
    public IReadOnlyList<AudioFormat> Formats => Core.Formats.Ordered;

    public IReadOnlyList<Preset> Presets => Core.Presets.All;

    public AudioFormat CurrentFormat => Formats[Math.Clamp(FormatIndex, 0, Formats.Count - 1)];

    public IReadOnlyList<QualityItem> QualityItems => _qualityItems;

    public IReadOnlyList<int> SampleRateValues => _sampleRates;

    public IReadOnlyList<int> ChannelValues => _channelList;

    public IReadOnlyList<string> SampleRateLabels => _sampleRates.Select(QualityOptions.SampleRateLabel).ToList();

    public IReadOnlyList<string> ChannelLabels => _channelList.Select(QualityOptions.ChannelLabel).ToList();

    private int _formatIndex;
    public int FormatIndex
    {
        get => _formatIndex;
        set
        {
            if (Set(ref _formatIndex, value))
            {
                RebuildFormatOptions(restoreSaved: false);
                Raise(nameof(Summary));
            }
        }
    }

    private int _qualityIndex;
    public int QualityIndex
    {
        get => _qualityIndex;
        set
        {
            if (Set(ref _qualityIndex, value)) Raise(nameof(Summary));
        }
    }

    private int _sampleRateIndex;
    public int SampleRateIndex
    {
        get => _sampleRateIndex;
        set
        {
            if (Set(ref _sampleRateIndex, value)) Raise(nameof(SampleRateValue));
        }
    }

    public int SampleRateValue => _sampleRates.Count > 0 ? _sampleRates[Math.Clamp(SampleRateIndex, 0, _sampleRates.Count - 1)] : 0;

    private int _channelIndex;
    public int ChannelIndex
    {
        get => _channelIndex;
        set
        {
            if (Set(ref _channelIndex, value)) Raise(nameof(ChannelValue));
        }
    }

    public int ChannelValue => _channelList.Count > 0 ? _channelList[Math.Clamp(ChannelIndex, 0, _channelList.Count - 1)] : 0;

    private double _compLevel;
    public double CompLevel
    {
        get => _compLevel;
        set => Set(ref _compLevel, value);
    }

    private bool _compVisible;
    public bool CompVisible
    {
        get => _compVisible;
        set => Set(ref _compVisible, value);
    }

    private bool _keepMetadata = true;
    public bool KeepMetadata
    {
        get => _keepMetadata;
        set => Set(ref _keepMetadata, value);
    }

    private bool _keepCover = true;
    public bool KeepCover
    {
        get => _keepCover;
        set => Set(ref _keepCover, value);
    }

    private double _workers = 2;
    public double Workers
    {
        get => _workers;
        set => Set(ref _workers, value);
    }

    private string _outputDir = string.Empty;
    public string OutputDir
    {
        get => _outputDir;
        set => Set(ref _outputDir, value);
    }

    private int _themeModeIndex;
    /// <summary>0=跟随系统，1=浅色，2=深色。</summary>
    public int ThemeModeIndex
    {
        get => _themeModeIndex;
        set => Set(ref _themeModeIndex, value);
    }

    private bool _sameAsSource = true;
    public bool SameAsSource
    {
        get => _sameAsSource;
        set => Set(ref _sameAsSource, value);
    }

    private string _template = "{name}";
    public string Template
    {
        get => _template;
        set => Set(ref _template, value);
    }

    private string _conflict = "rename";
    public string Conflict
    {
        get => _conflict;
        set => Set(ref _conflict, value);
    }

    // ------------------------------------------------------------------ 展示
    private string _engineVersion = "引擎加载中…";
    public string EngineVersion
    {
        get => _engineVersion;
        set => Set(ref _engineVersion, value);
    }

    private string _statusText = "就绪：添加音频后即可开始";
    public string StatusText
    {
        get => _statusText;
        set => Set(ref _statusText, value);
    }

    private double _overallProgress;
    public double OverallProgress
    {
        get => _overallProgress;
        set => Set(ref _overallProgress, value);
    }

    private bool _isRunning;
    public bool IsRunning
    {
        get => _isRunning;
        set
        {
            if (Set(ref _isRunning, value)) Raise(nameof(CanEditSettings));
        }
    }

    public bool CanEditSettings => !IsRunning;

    private string _logText = string.Empty;
    public string LogText
    {
        get => _logText;
        set => Set(ref _logText, value);
    }

    private bool _logVisible;
    public bool LogVisible
    {
        get => _logVisible;
        set => Set(ref _logVisible, value);
    }

    public string Summary => QualityOptions.Summarize(CurrentFormat, _qualityItems.ElementAtOrDefault(QualityIndex));

    public string OutputSummary => SameAsSource
        ? "输出到源文件所在文件夹"
        : $"输出到 {(string.IsNullOrWhiteSpace(OutputDir) ? "（未选择）" : OutputDir)}";

    // ------------------------------------------------------------------ 格式联动
    private void RebuildFormatOptions(bool restoreSaved)
    {
        var fmt = CurrentFormat;
        _qualityItems.Clear();
        _qualityItems.AddRange(QualityOptions.Build(fmt));
        QualityIndex = restoreSaved
            ? Math.Clamp(_settings.QualityIndex, 0, _qualityItems.Count - 1)
            : QualityOptions.DefaultIndex(fmt, _qualityItems);

        _sampleRates = QualityOptions.SampleRates(fmt);
        var savedRate = restoreSaved ? _settings.SampleRate : 0;
        SampleRateIndex = Math.Max(0, _sampleRates.IndexOf(savedRate));

        _channelList = QualityOptions.ChannelList(fmt);
        var savedChannels = restoreSaved ? _settings.Channels : (fmt.MaxChannels <= 1 ? 1 : 0);
        ChannelIndex = Math.Max(0, _channelList.IndexOf(savedChannels));

        if (fmt.CompLevels is { } levels)
        {
            CompVisible = true;
            var level = restoreSaved && _settings.CompLevel >= 0 ? _settings.CompLevel : fmt.DefaultComp();
            CompLevel = Math.Clamp(level, levels.Min, levels.Max);
        }
        else
        {
            CompVisible = false;
            CompLevel = 0;
        }

        Raise(nameof(QualityItems));
        Raise(nameof(SampleRateValues));
        Raise(nameof(ChannelValues));
        Raise(nameof(SampleRateLabels));
        Raise(nameof(ChannelLabels));
        Raise(nameof(Summary));
        Raise(nameof(OutputSummary));
    }

    public ConvertSettings BuildSettings()
    {
        var fmt = CurrentFormat;
        var quality = _qualityItems.ElementAtOrDefault(QualityIndex);
        var bitrate = quality is { Bitrate: > 0 } ? quality.Bitrate : fmt.DefaultBitrate;
        var depth = !fmt.Lossless
            ? 0
            : quality is { BitDepth: > 0 } ? quality.BitDepth : fmt.DefaultBitDepth;
        var compLevel = fmt.CompLevels is { } levels
            ? Math.Clamp((int)Math.Round(CompLevel), levels.Min, levels.Max)
            : -1;

        return new ConvertSettings
        {
            FormatKey = fmt.Key,
            Bitrate = bitrate,
            VbrIndex = quality?.VbrIndex ?? -1,
            SampleRate = SampleRateValue,
            Channels = ChannelValue,
            BitDepth = depth,
            CompLevel = compLevel,
            KeepMetadata = KeepMetadata,
            KeepCover = KeepCover,
        };
    }

    public void ApplyPreset(Preset preset)
    {
        var index = Formats.ToList().FindIndex(f => f.Key == preset.FormatKey);
        if (index < 0) return;
        FormatIndex = index;

        var qualityIndex = -1;
        if (preset.VbrIndex >= 0) qualityIndex = _qualityItems.ToList().FindIndex(q => q.VbrIndex == preset.VbrIndex);
        else if (preset.Bitrate > 0) qualityIndex = _qualityItems.ToList().FindIndex(q => q.Bitrate == preset.Bitrate);
        else if (preset.BitDepth > 0) qualityIndex = _qualityItems.ToList().FindIndex(q => q.BitDepth == preset.BitDepth);
        if (qualityIndex >= 0) QualityIndex = qualityIndex;

        SampleRateIndex = preset.SampleRate > 0 ? Math.Max(0, _sampleRates.IndexOf(preset.SampleRate)) : 0;
        ChannelIndex = preset.Channels > 0 ? Math.Max(0, _channelList.IndexOf(preset.Channels)) : 0;
        if (preset.CompLevel >= 0 && CurrentFormat.CompLevels is { } levels)
        {
            CompLevel = Math.Clamp(preset.CompLevel, levels.Min, levels.Max);
        }
        AppendLog($"已套用预设：{preset.Label} —— {preset.Hint}");
    }

    // ------------------------------------------------------------------ 文件
    public async Task AddPathsAsync(IEnumerable<string> paths)
    {
        var files = new List<string>();
        foreach (var path in paths)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    files.AddRange(Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories)
                        .Where(AudioExts.IsAudioLike));
                }
                else if (File.Exists(path) && AudioExts.IsAudioLike(path))
                {
                    files.Add(path);
                }
            }
            catch
            {
                // 忽略无权限的目录
            }
        }

        var existing = Jobs.Select(j => j.SourcePath).ToHashSet(StringComparer.OrdinalIgnoreCase);
        files = files.Where(f => !existing.Contains(f)).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (files.Count == 0)
        {
            StatusText = "没有新增可识别的音频文件";
            return;
        }

        StatusText = $"正在分析 {files.Count} 个文件…";
        var added = 0;
        foreach (var file in files)
        {
            var info = await Task.Run(() => AudioProbe.Probe(file));
            _nextJobId++;
            var job = new Job { Id = _nextJobId, SourcePath = file, Info = info };
            if (!info.Ok)
            {
                job.Status = JobStatus.Failed;
                job.Message = string.IsNullOrWhiteSpace(info.Error) ? "无法识别的音频" : info.Error;
            }
            Jobs.Add(job);
            added++;
        }

        StatusText = $"已添加 {added} 个文件";
        AppendLog($"添加 {added} 个文件");
        RecomputeOverall();
        _ = FillCoversAsync();
    }

    /// <summary>后台把内嵌封面导出成缩略图，列表里就能直接看到专辑封面。</summary>
    private async Task FillCoversAsync()
    {
        var pending = Jobs.Where(j => j.Info.HasCover && string.IsNullOrEmpty(j.CoverPath)).ToList();
        if (pending.Count == 0) return;
        foreach (var job in pending)
        {
            var path = await Task.Run(() => CoverCache.TryExtract(job.SourcePath));
            if (path is not null)
            {
                _dispatcher.TryEnqueue(() => job.CoverPath = path);
            }
        }
    }

    /// <summary>把当前主题模式写入 settings.json（切换主题后重启要用）。</summary>
    public void SaveThemePreference()
    {
        _settings.ThemeMode = ThemeModeIndex;
        _settings.Save();
    }

    public void RemoveSelected(IEnumerable<Job> jobs)
    {
        foreach (var job in jobs.ToList())
        {
            if (job.Status == JobStatus.Running) continue;
            Jobs.Remove(job);
        }
        RecomputeOverall();
    }

    public void Clear()
    {
        if (IsRunning)
        {
            StatusText = "正在转换，请先取消";
            return;
        }
        Jobs.Clear();
        Task.Run(CoverCache.Clear);
        OverallProgress = 0;
        StatusText = "已清空";
    }

    // ------------------------------------------------------------------ 运行
    public void Start()
    {
        if (IsRunning) return;
        var targets = Jobs.Where(j => j.Status != JobStatus.Running && j.Info.Ok).ToList();
        if (targets.Count == 0)
        {
            StatusText = "没有可转换的文件";
            return;
        }
        if (!SameAsSource && string.IsNullOrWhiteSpace(OutputDir))
        {
            StatusText = "请先选择输出文件夹";
            return;
        }

        var settings = BuildSettings();
        SaveSettings(settings);

        IsRunning = true;
        OverallProgress = 0;
        StatusText = "转换中…";
        Raise(nameof(OutputSummary));
        _queue.Start(
            targets,
            CurrentFormat,
            settings,
            SameAsSource ? null : OutputDir,
            Template,
            Conflict,
            (int)Math.Round(Workers));
    }

    public void Cancel()
    {
        if (!IsRunning) return;
        AppendLog("已请求取消，正在终止 ffmpeg…");
        _queue.Cancel();
    }

    private void SaveSettings(ConvertSettings settings)
    {
        _settings.FormatKey = CurrentFormat.Key;
        _settings.QualityIndex = QualityIndex;
        _settings.SampleRate = settings.SampleRate;
        _settings.Channels = settings.Channels;
        _settings.CompLevel = settings.CompLevel;
        _settings.KeepMetadata = settings.KeepMetadata;
        _settings.KeepCover = settings.KeepCover;
        _settings.Workers = (int)Math.Round(Workers);
        _settings.Template = Template;
        _settings.Conflict = Conflict;
        _settings.SameAsSource = SameAsSource;
        _settings.ThemeMode = ThemeModeIndex;
        _settings.OutputDir = OutputDir;
        _settings.Save();
    }

    // ------------------------------------------------------------------ 事件
    private void OnJobUpdated(Job job)
    {
        job.RaiseAll();
        RecomputeOverall();
    }

    private void OnFinished(QueueStats stats)
    {
        IsRunning = false;
        var style = stats.Failed > 0 ? "失败" : "完成";
        StatusText = $"{style} {stats.Ok} · 失败 {stats.Failed} · 跳过 {stats.Skipped} · 取消 {stats.Canceled} · 用时 {stats.Elapsed.TotalSeconds:0.0}s";
        RecomputeOverall();
    }

    private void RecomputeOverall()
    {
        if (Jobs.Count == 0)
        {
            OverallProgress = 0;
            return;
        }
        var sum = Jobs.Sum(j => j.Status switch
        {
            JobStatus.Done => 100.0,
            JobStatus.Running => j.Progress,
            _ => 0.0,
        });
        OverallProgress = sum / Jobs.Count;
    }

    private void AppendLog(string line)
    {
        _logBuilder.Append('[').Append(DateTime.Now.ToString("HH:mm:ss")).Append("] ").AppendLine(line);
        var text = _logBuilder.ToString();
        if (text.Length > 20000) text = text[^20000..];
        LogText = text;
    }

    public string LogTail => LogText;

    // ------------------------------------------------------------------ INPC
    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(propertyName);
        return true;
    }

    private void Raise(string? propertyName)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
