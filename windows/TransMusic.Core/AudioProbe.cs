using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace TransMusic.Core;

/// <summary>
/// 探测源文件信息：跑一次 <c>ffmpeg -i file</c>，从 stderr 里解析时长 / 码率 / 采样率 / 声道 / 编码 / 封面。
/// （与 Python 端 engine._ffmpeg_probe 的解析规则一致）
/// </summary>
public static class AudioProbe
{
    private static readonly Regex DurationRe = new(@"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", RegexOptions.Compiled);
    private static readonly Regex AudioRe = new(
        @"Stream #\d+:\d+.*?Audio:\s*([A-Za-z0-9_\-]+).*?(\d+)\s*Hz,\s*([^,]+)(?:,\s*([a-z0-9]+))?",
        RegexOptions.Compiled);
    private static readonly Regex BitrateRe = new(@"bitrate:\s*(\d+)\s*kb/s", RegexOptions.Compiled);
    private static readonly Regex CoverRe = new(@"Video:\s*(?:mjpeg|png|bmp|gif)", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex ChannelsRe = new(@"(\d+)\s*channels?", RegexOptions.Compiled);
    private static readonly Regex ChannelsLayoutRe = new(@"Hz,\s*([a-z0-9]+(?:\()?)", RegexOptions.Compiled);

    public static MediaInfo Probe(string path)
    {
        var container = Path.GetExtension(path).TrimStart('.').ToLowerInvariant();
        if (!File.Exists(path))
        {
            return new MediaInfo { Path = path, Container = container, Ok = false, Error = "文件不存在" };
        }

        string ffmpeg;
        try
        {
            ffmpeg = FfmpegLocator.Find();
        }
        catch (Exception ex)
        {
            return new MediaInfo { Path = path, Container = container, Ok = false, Error = ex.Message };
        }

        var psi = new ProcessStartInfo(ffmpeg)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardErrorEncoding = Encoding.UTF8,
            StandardOutputEncoding = Encoding.UTF8,
        };
        psi.ArgumentList.Add("-hide_banner");
        psi.ArgumentList.Add("-i");
        psi.ArgumentList.Add(path);

        string text;
        try
        {
            using var proc = Process.Start(psi);
            if (proc is null)
            {
                return new MediaInfo { Path = path, Container = container, Ok = false, Error = "无法启动 ffmpeg" };
            }
            text = proc.StandardError.ReadToEnd() + proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(15000);
        }
        catch (Exception ex)
        {
            return new MediaInfo { Path = path, Container = container, Ok = false, Error = ex.Message };
        }

        var duration = 0.0;
        var bitrate = 0;
        var sampleRate = 0;
        var channels = 0;
        var bitDepth = 0;
        var codec = string.Empty;

        var m = DurationRe.Match(text);
        if (m.Success)
        {
            duration = int.Parse(m.Groups[1].Value) * 3600
                       + int.Parse(m.Groups[2].Value) * 60
                       + double.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture);
        }

        var am = AudioRe.Match(text);
        if (am.Success)
        {
            codec = am.Groups[1].Value.ToLowerInvariant();
            sampleRate = int.TryParse(am.Groups[2].Value, out var sr) ? sr : 0;
            var layout = am.Groups[3].Value.Trim().ToLowerInvariant();
            channels = layout switch
            {
                "mono" => 1,
                "stereo" => 2,
                _ => 0,
            };
            if (channels == 0)
            {
                var cm = ChannelsRe.Match(text);
                if (cm.Success) channels = int.Parse(cm.Groups[1].Value);
            }
            var fmt = am.Groups[4].Value;
            if (fmt.Length >= 2 && fmt[0] == 's' && int.TryParse(fmt[1..], out var bd)) bitDepth = bd;
        }

        var bm = BitrateRe.Match(text);
        if (bm.Success) bitrate = int.Parse(bm.Groups[1].Value) * 1000;

        var hasCover = CoverRe.IsMatch(text);
        var ok = am.Success;
        var error = ok ? string.Empty : "没有音频流";

        if (!ok && File.Exists(path))
        {
            // 拿不到音频流时，退回用文件大小估码率
            try
            {
                var size = new FileInfo(path).Length;
                if (duration > 0) bitrate = (int)(size * 8 / duration);
            }
            catch
            {
                // 忽略
            }
        }

        return new MediaInfo
        {
            Path = path,
            Container = container,
            DurationSeconds = duration,
            Bitrate = bitrate,
            SampleRate = sampleRate,
            Channels = channels,
            BitDepth = bitDepth,
            Codec = codec,
            HasCover = hasCover,
            Ok = ok && (duration > 0 || sampleRate > 0),
            Error = error,
        };
    }
}
