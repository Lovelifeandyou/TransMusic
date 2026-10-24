namespace TransMusic.Core;

/// <summary>质量下拉项：CBR 码率 / VBR 档位 / 无损位深，三者只会命中一个。</summary>
public sealed record QualityItem(string Label, int Bitrate = 0, int VbrIndex = -1, int BitDepth = 0);

/// <summary>
/// 质量、采样率、声道可选项的计算规则。
/// 与 Android 端 ui/SettingsState.kt、Python 端 formats.py 的语义保持一致。
/// </summary>
public static class QualityOptions
{
    public static string DepthLabel(int depth) => depth switch
    {
        16 => "16 bit · CD 级",
        24 => "24 bit · Hi-Res",
        32 => "32 bit · 高精度",
        _ => $"{depth} bit",
    };

    public static List<QualityItem> Build(AudioFormat fmt)
    {
        var items = new List<QualityItem>();
        if (fmt.Lossless)
        {
            foreach (var depth in fmt.BitDepths) items.Add(new QualityItem(DepthLabel(depth), BitDepth: depth));
        }
        else
        {
            foreach (var bitrate in fmt.Bitrates) items.Add(new QualityItem($"CBR {bitrate} kbps", Bitrate: bitrate));
            for (var i = 0; i < fmt.VbrLabels.Count; i++) items.Add(new QualityItem(fmt.VbrLabels[i], VbrIndex: i));
        }
        if (items.Count == 0) items.Add(new QualityItem("默认"));
        return items;
    }

    public static int DefaultIndex(AudioFormat fmt, IReadOnlyList<QualityItem> items)
    {
        var index = fmt.Lossless
            ? items.ToList().FindIndex(i => i.BitDepth == fmt.DefaultBitDepth)
            : items.ToList().FindIndex(i => i.Bitrate == fmt.DefaultBitrate);
        return index >= 0 ? index : 0;
    }

    public static List<int> SampleRates(AudioFormat fmt)
    {
        var list = new List<int> { 0 };
        if (fmt.SampleRates.Count > 0) list.AddRange(fmt.SampleRates);
        else list.AddRange(new[] { 22050, 44100, 48000, 88200, 96000, 176400, 192000 });
        return list;
    }

    public static string SampleRateLabel(int sampleRate) => sampleRate == 0 ? "保持原样" : $"{sampleRate} Hz";

    public static List<int> ChannelList(AudioFormat fmt)
        => fmt.MaxChannels <= 1 ? new List<int> { 1 } : new List<int> { 0, 1, 2 };

    public static string ChannelLabel(int channels) => channels switch
    {
        0 => "保持原样",
        1 => "单声道",
        2 => "立体声",
        _ => $"{channels} 声道",
    };

    /// <summary>摘要文字，例如「MP3 · CBR 320 kbps」。</summary>
    public static string Summarize(AudioFormat fmt, QualityItem? quality)
    {
        var label = fmt.Label.Split("  ·")[0].Trim();
        var detail = quality?.Label ?? (fmt.Lossless ? $"{fmt.DefaultBitDepth} bit" : $"CBR {fmt.DefaultBitrate} kbps");
        return $"{label} · {detail}";
    }
}
