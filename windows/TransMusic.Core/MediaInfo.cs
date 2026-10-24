namespace TransMusic.Core;

/// <summary>源文件的媒体信息（由 ffmpeg 探测得到）。</summary>
public sealed class MediaInfo
{
    public string Path { get; init; } = string.Empty;
    public double DurationSeconds { get; init; }
    public int Bitrate { get; init; }
    public int SampleRate { get; init; }
    public int Channels { get; init; }
    public int BitDepth { get; init; }
    public string Codec { get; init; } = string.Empty;
    public string Container { get; init; } = string.Empty;
    public bool HasCover { get; init; }
    public string Title { get; init; } = string.Empty;
    public string Artist { get; init; } = string.Empty;
    public string Album { get; init; } = string.Empty;
    public bool Ok { get; init; } = true;
    public string Error { get; init; } = string.Empty;

    public string DurationText
    {
        get
        {
            if (DurationSeconds <= 0) return "--:--";
            var total = (int)Math.Round(DurationSeconds);
            var h = total / 3600;
            var m = total % 3600 / 60;
            var s = total % 60;
            return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m:00}:{s:00}";
        }
    }

    public string BitrateText => Bitrate <= 0 ? "-" : Bitrate >= 1000 ? $"{Bitrate / 1000} kbps" : $"{Bitrate} bps";

    public string SampleRateText => SampleRate <= 0 ? "-" : $"{SampleRate / 1000.0:0.#} kHz";

    public string ChannelsText => Channels switch
    {
        <= 0 => "-",
        1 => "单声道",
        2 => "立体声",
        _ => $"{Channels} 声道",
    };
}
