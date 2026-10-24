namespace TransMusic.Core;

/// <summary>
/// 一次转换的全部参数。与 Android 端 ConvertSettings、Python 端 engine.ConvertSettings 字段一一对应。
/// 0 表示「保持原样」。
/// </summary>
public sealed record ConvertSettings
{
    public string FormatKey { get; init; } = "mp3";

    /// <summary>失真格式的 CBR 码率（kbps）。</summary>
    public int Bitrate { get; init; } = 320;

    /// <summary>-1 = 用 CBR；&gt;=0 = 使用该 VBR 档位。</summary>
    public int VbrIndex { get; init; } = -1;

    /// <summary>采样率，0 = 保持原样。</summary>
    public int SampleRate { get; init; }

    /// <summary>声道数（1/2），0 = 保持原样。</summary>
    public int Channels { get; init; }

    /// <summary>无损格式的位深（16/24/32）。</summary>
    public int BitDepth { get; init; }

    /// <summary>无损压缩级别，-1 = 用格式默认值。</summary>
    public int CompLevel { get; init; } = -1;

    public bool KeepMetadata { get; init; } = true;

    public bool KeepCover { get; init; } = true;
}
