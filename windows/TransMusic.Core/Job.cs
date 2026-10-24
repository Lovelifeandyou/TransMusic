using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace TransMusic.Core;

public enum JobStatus
{
    Waiting,
    Running,
    Done,
    Failed,
    Skipped,
    Canceled,
}

/// <summary>一条转换任务（界面直接绑定）。</summary>
public sealed class Job : INotifyPropertyChanged
{
    public int Id { get; init; }

    public required string SourcePath { get; init; }

    public MediaInfo Info { get; set; } = new();

    public AudioFormat? Format { get; set; }

    public ConvertSettings? Settings { get; set; }

    public string? OutputPath { get; set; }

    public string FileName => Path.GetFileName(SourcePath);

    public string Extension => Path.GetExtension(SourcePath).TrimStart('.').ToUpperInvariant();

    private JobStatus _status = JobStatus.Waiting;
    public JobStatus Status
    {
        get => _status;
        set
        {
            if (Set(ref _status, value))
            {
                Raise(nameof(StatusText));
                Raise(nameof(ProgressText));
            }
        }
    }

    private double _progress;
    public double Progress
    {
        get => _progress;
        set
        {
            if (Set(ref _progress, value)) Raise(nameof(ProgressText));
        }
    }

    private string _speed = string.Empty;
    public string Speed
    {
        get => _speed;
        set
        {
            if (Set(ref _speed, value)) Raise(nameof(ProgressText));
        }
    }

    private string _message = string.Empty;
    public string Message
    {
        get => _message;
        set
        {
            if (Set(ref _message, value)) Raise(nameof(StatusText));
        }
    }

    private string? _coverPath;
    /// <summary>封面缓存文件路径（没有封面时为 null）。</summary>
    public string? CoverPath
    {
        get => _coverPath;
        set
        {
            if (Set(ref _coverPath, value)) Raise(nameof(HasCoverImage));
        }
    }

    public bool HasCoverImage => !string.IsNullOrWhiteSpace(_coverPath);

    private TimeSpan _elapsed;
    public TimeSpan Elapsed
    {
        get => _elapsed;
        set => Set(ref _elapsed, value);
    }

    public string StatusText => Status switch
    {
        JobStatus.Waiting => "等待",
        JobStatus.Running => "转换中",
        JobStatus.Done => "完成",
        JobStatus.Failed => string.IsNullOrWhiteSpace(Message) ? "失败" : $"失败：{Message}",
        JobStatus.Skipped => "已跳过",
        JobStatus.Canceled => "已取消",
        _ => string.Empty,
    };

    public string ProgressText => Status switch
    {
        JobStatus.Done => "100%",
        JobStatus.Running => string.IsNullOrEmpty(Speed) ? $"{Progress:0}%" : $"{Progress:0}%  {Speed}",
        _ => string.Empty,
    };

    public string MetaText
    {
        get
        {
            var raw = string.IsNullOrWhiteSpace(Info.Codec) ? Extension : Info.Codec.ToUpperInvariant();
            // ffmpeg 解 DSD 报 dsd_lsbf_planar 之类，列表里统一显示 DSD
            var codec = raw.StartsWith("DSD", StringComparison.OrdinalIgnoreCase) ||
                        raw.Equals("DSF", StringComparison.OrdinalIgnoreCase) ||
                        raw.Equals("DFF", StringComparison.OrdinalIgnoreCase)
                ? "DSD"
                : raw;
            var parts = new List<string> { codec, Info.DurationText, Info.BitrateText, Info.SampleRateText, Info.ChannelsText };
            return string.Join(" · ", parts.Where(p => !string.IsNullOrWhiteSpace(p) && p != "-"));
        }
    }

    public bool ShowProgress => Status is JobStatus.Running or JobStatus.Done;

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(propertyName);
        if (propertyName is nameof(Status)) Raise(nameof(ShowProgress));
        return true;
    }

    private void Raise(string? propertyName)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    /// <summary>一次性刷新所有只读展示属性（列表行重建时用）。</summary>
    public void RaiseAll()
    {
        foreach (var name in new[] { nameof(Status), nameof(Progress), nameof(StatusText), nameof(ProgressText), nameof(MetaText), nameof(ShowProgress), nameof(Message), nameof(CoverPath), nameof(HasCoverImage) })
        {
            Raise(name);
        }
    }
}
