using System.Text.RegularExpressions;

namespace TransMusic.Core;

/// <summary>输出路径解析：文件名模板、重名冲突策略、唯一命名。</summary>
public static class OutputPathResolver
{
    private static readonly Regex BadChars = new("[<>:\"/\\\\|?*\\x00-\\x1f]");

    public static string Sanitize(string name)
    {
        var cleaned = BadChars.Replace(name, "_").Trim().Trim('.');
        if (cleaned.Length > 150) cleaned = cleaned[..150];
        return string.IsNullOrWhiteSpace(cleaned) ? "output" : cleaned;
    }

    /// <summary>模板占位符：{name} {artist} {title} {album} {index} {ext} {bitrate}</summary>
    public static string RenderFileName(string template, string sourcePath, AudioFormat fmt, MediaInfo info, int index)
    {
        var stem = Path.GetFileNameWithoutExtension(sourcePath);
        var values = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["name"] = stem,
            ["artist"] = info.Artist,
            ["title"] = string.IsNullOrWhiteSpace(info.Title) ? stem : info.Title,
            ["album"] = info.Album,
            ["index"] = index.ToString("00"),
            ["bitrate"] = info.Bitrate > 0 ? (info.Bitrate / 1000).ToString() : string.Empty,
            ["ext"] = fmt.Ext,
        };
        var text = string.IsNullOrWhiteSpace(template) ? "{name}" : template;
        foreach (var (key, value) in values) text = text.Replace("{" + key + "}", value, StringComparison.Ordinal);
        text = Sanitize(text);
        return text + "." + fmt.Ext;
    }

    public static string Unique(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path) ?? ".";
        var stem = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (var i = 1; i < 10000; i++)
        {
            var candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
        return Path.Combine(dir, $"{stem} ({DateTime.Now:HHmmss}){ext}");
    }

    /// <summary>
    /// 返回目标路径；conflict="skip" 且目标已存在时返回 null。
    /// 若目标恰好是源文件本身（同格式原地转换），自动改名避免覆盖源文件。
    /// </summary>
    public static string? Resolve(
        string sourcePath,
        string? outputDir,
        AudioFormat fmt,
        MediaInfo info,
        string template = "{name}",
        string conflict = "rename",
        int index = 1)
    {
        var baseDir = string.IsNullOrWhiteSpace(outputDir)
            ? Path.GetDirectoryName(Path.GetFullPath(sourcePath)) ?? "."
            : outputDir!;
        var fileName = RenderFileName(template, sourcePath, fmt, info, index);
        var dst = Path.Combine(baseDir, fileName);

        var same = string.Equals(Path.GetFullPath(dst), Path.GetFullPath(sourcePath), StringComparison.OrdinalIgnoreCase);
        if (same)
        {
            var renamed = Sanitize(Path.GetFileNameWithoutExtension(sourcePath)) + "_converted." + fmt.Ext;
            return Unique(Path.Combine(baseDir, renamed));
        }

        if (!File.Exists(dst)) return dst;
        return conflict switch
        {
            "overwrite" => dst,
            "skip" => null,
            _ => Unique(dst),
        };
    }
}
