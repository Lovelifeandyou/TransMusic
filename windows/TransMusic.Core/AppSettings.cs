using System.Text.Json;
using System.Text.Json.Serialization;

namespace TransMusic.Core;

/// <summary>界面参数持久化（%APPDATA%\TransMusic\settings.json）。</summary>
public sealed class AppSettings
{
    public string FormatKey { get; set; } = "mp3";
    public int QualityIndex { get; set; }
    public int SampleRate { get; set; }
    public int Channels { get; set; }
    public int CompLevel { get; set; } = -1;
    public bool KeepMetadata { get; set; } = true;
    public bool KeepCover { get; set; } = true;
    public int Workers { get; set; } = 2;
    public string Template { get; set; } = "{name}";
    public string Conflict { get; set; } = "rename";
    public bool SameAsSource { get; set; } = true;
    public string OutputDir { get; set; } = string.Empty;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static string SettingsPath
    {
        get
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "TransMusic");
            return Path.Combine(dir, "settings.json");
        }
    }

    public static AppSettings Load()
    {
        try
        {
            var path = SettingsPath;
            if (!File.Exists(path)) return new AppSettings();
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<AppSettings>(json, JsonOptions) ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save()
    {
        try
        {
            var path = SettingsPath;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, JsonSerializer.Serialize(this, JsonOptions));
        }
        catch
        {
            // 存不下就算了，不影响转换
        }
    }
}
