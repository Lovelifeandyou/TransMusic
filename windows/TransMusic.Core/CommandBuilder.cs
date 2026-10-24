using System.Text;

namespace TransMusic.Core;

/// <summary>DSD（.dsf/.dff）相关的判定与参数换算。</summary>
public static class DsdSupport
{
    /// <summary>ffmpeg 解码后的采样率（8x/16x/32x）与 DSD 原生采样率（64x/128x/256x）。</summary>
    public static readonly IReadOnlySet<int> SourceRates = new HashSet<int>
    {
        352800, 705600, 1411200, 2822400, 5644800, 11289600, 22579200,
    };

    public static bool IsDsdSource(string? codec, int sampleRate, string? path = null)
    {
        if (!string.IsNullOrWhiteSpace(codec) &&
            codec.StartsWith("dsd", StringComparison.OrdinalIgnoreCase)) return true;
        if (SourceRates.Contains(sampleRate)) return true;
        if (!string.IsNullOrWhiteSpace(path))
        {
            var ext = Path.GetExtension(path);
            if (ext.Equals(".dsf", StringComparison.OrdinalIgnoreCase) ||
                ext.Equals(".dff", StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    /// <summary>DSD 源默认降到 PCM 常用倍频：DSD64→88.2k、DSD128→176.4k、DSD256→352.8k。</summary>
    public static int PcmTarget(int srcSampleRate)
    {
        var factor = srcSampleRate > 0 ? (int)Math.Round(srcSampleRate / 44100.0) : 0;
        return factor switch
        {
            8 or 64 => 88200,     // DSD64
            16 or 128 => 176400,  // DSD128
            32 or 256 => 352800,  // DSD256
            _ => factor <= 8 ? 88200 : factor <= 16 ? 176400 : 352800,
        };
    }
}

/// <summary>
/// 把「目标格式 + 设置 + 源信息」翻译成 ffmpeg 参数列表。
/// 逻辑与 Python 端 engine.build_command、Android 端 CommandBuilder.kt 严格一致，
/// tests/parity_winui_test.py 会逐参数对比三端输出。
/// </summary>
public static class CommandBuilder
{
    public static int Nearest(IReadOnlyList<int> values, int target)
    {
        if (values.Count == 0) return target;
        var best = values[0];
        foreach (var v in values)
        {
            if (Math.Abs(v - target) < Math.Abs(best - target)) best = v;
            else if (Math.Abs(v - target) == Math.Abs(best - target) && v < best) best = v;
        }
        return best;
    }

    /// <summary>返回 -ar 的取值；0 表示沿用源采样率。</summary>
    public static int ResolveSampleRate(
        AudioFormat fmt,
        int request,
        int srcSampleRate,
        string srcCodec = "",
        string? srcPath = null)
    {
        if (request > 0)
        {
            if (fmt.SampleRates.Count > 0 && !fmt.SampleRates.Contains(request)) return Nearest(fmt.SampleRates, request);
            return request;
        }
        if (srcSampleRate <= 0) return 0;

        var target = srcSampleRate;
        if (DsdSupport.IsDsdSource(srcCodec, srcSampleRate, srcPath))
        {
            // DSD 保持原样等于输出 352.8kHz 以上的巨量 PCM，默认降到 88.2kHz 系
            target = DsdSupport.PcmTarget(srcSampleRate);
        }
        if (fmt.SampleRates.Count > 0 && !fmt.SampleRates.Contains(target))
        {
            target = Nearest(fmt.SampleRates, target);
        }
        return target == srcSampleRate ? 0 : target;
    }

    public static int ResolveChannels(AudioFormat fmt, int request)
    {
        if (fmt.MaxChannels > 0 && request > fmt.MaxChannels) return fmt.MaxChannels;
        if (fmt.MaxChannels == 1) return 1;
        return request;
    }

    public static int ResolveBitDepth(AudioFormat fmt, int request)
    {
        if (fmt.SampleFmts.Count == 0) return 0;
        if (fmt.SampleFmts.ContainsKey(request)) return request;
        if (fmt.DefaultBitDepth > 0 && fmt.SampleFmts.ContainsKey(fmt.DefaultBitDepth)) return fmt.DefaultBitDepth;
        return fmt.SampleFmts.Keys.First();
    }

    public static List<string> Build(
        string srcPath,
        string dstPath,
        AudioFormat fmt,
        ConvertSettings settings,
        MediaInfo? info = null,
        bool reportProgress = true)
    {
        info ??= new MediaInfo();
        var cmd = new List<string>
        {
            "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
            "-i", srcPath,
        };

        var copyCover = fmt.Cover && settings.KeepCover && info.HasCover;
        cmd.AddRange(new[] { "-map", "0:a:0" });
        if (copyCover) cmd.AddRange(new[] { "-map", "0:v:0?", "-c:v", "copy", "-disposition:v", "attached_pic" });
        else cmd.Add("-vn");
        cmd.AddRange(new[] { "-sn", "-dn" });

        // ---- 编码器 ----
        var depth = fmt.Lossless ? ResolveBitDepth(fmt, settings.BitDepth) : 0;
        var codec = fmt.Codec;
        if (fmt.Lossless && fmt.SampleFmts.Count > 0 && depth > 0)
        {
            var mapped = fmt.SampleFmts[depth];
            if (mapped.StartsWith("pcm_", StringComparison.Ordinal)) codec = mapped;
        }
        cmd.AddRange(new[] { "-c:a", codec });

        if (fmt.Lossless && fmt.SampleFmts.Count > 0 && depth > 0 && !codec.StartsWith("pcm_", StringComparison.Ordinal))
        {
            cmd.AddRange(new[] { "-sample_fmt", fmt.SampleFmts[depth] });
        }

        // ---- 质量 ----
        if (fmt.Lossless)
        {
            if (fmt.CompLevels is { } levels)
            {
                var level = settings.CompLevel >= 0 ? settings.CompLevel : fmt.DefaultComp();
                level = Math.Clamp(level, levels.Min, levels.Max);
                cmd.AddRange(new[] { "-compression_level", level.ToString() });
            }
        }
        else if (settings.VbrIndex >= 0 && fmt.VbrValues.Count > 0)
        {
            var q = fmt.VbrValues[Math.Min(settings.VbrIndex, fmt.VbrValues.Count - 1)];
            if (fmt.Key == "opus") cmd.AddRange(new[] { "-b:a", $"{q}k", "-vbr", "on" });
            else cmd.AddRange(new[] { "-q:a", q.ToString() });
        }
        else
        {
            var bitrate = settings.Bitrate > 0 ? settings.Bitrate : fmt.DefaultBitrate;
            if (bitrate > 0) cmd.AddRange(new[] { "-b:a", $"{bitrate}k" });
        }

        if (fmt.Key == "opus") cmd.AddRange(new[] { "-compression_level", "10", "-application", "audio" });

        // ---- 采样率 / 声道 ----
        var sr = ResolveSampleRate(fmt, settings.SampleRate, info.SampleRate, info.Codec, srcPath);
        var dsd = DsdSupport.IsDsdSource(info.Codec, info.SampleRate, srcPath);
        if (dsd && sr > 0 && sr <= 96000)
        {
            // DSD 的噪声整形集中在超声波段，降到低采样率 PCM 前先低通，避免混叠
            cmd.AddRange(new[] { "-af", "lowpass=f=30000" });
        }
        if (sr > 0) cmd.AddRange(new[] { "-ar", sr.ToString() });
        var ch = ResolveChannels(fmt, settings.Channels);
        if (ch > 0) cmd.AddRange(new[] { "-ac", ch.ToString() });

        // ---- 元数据 / 封面 ----
        if (settings.KeepMetadata && fmt.Tags) cmd.AddRange(new[] { "-map_metadata", "0" });
        else cmd.AddRange(new[] { "-map_metadata", "-1" });
        cmd.AddRange(new[] { "-map_chapters", "-1" });
        if (fmt.Key == "mp3") cmd.AddRange(new[] { "-id3v2_version", "3", "-write_id3v1", "1" });
        if (fmt.Muxer == "ipod") cmd.AddRange(new[] { "-movflags", "+faststart" });

        if (reportProgress) cmd.AddRange(new[] { "-progress", "pipe:1", "-nostats" });
        cmd.Add(dstPath);
        return cmd;
    }

    /// <summary>拼成可复制的命令行（仅用于界面展示/排查）。</summary>
    public static string ToCommandLine(IEnumerable<string> args)
    {
        var sb = new StringBuilder();
        foreach (var arg in args)
        {
            if (sb.Length > 0) sb.Append(' ');
            sb.Append(arg.Contains(' ') || arg.Contains('\\') ? $"\"{arg}\"" : arg);
        }
        return sb.ToString();
    }
}
