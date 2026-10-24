using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;
using TransMusic.Core;
using Windows.UI;

namespace TransMusic.App.Converters;

/// <summary>bool → Visibility（x:Bind 里做显示/隐藏用）。</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
        => value is true ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => value is Visibility.Visible;
}

/// <summary>bool 取反（例如「同源输出」勾上时禁用目录输入框）。</summary>
public sealed class InverseBoolConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
        => value is not true;

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => value is not true;
}

/// <summary>任务状态 → 状态色（与其它端一致的语义色）。</summary>
public sealed class StatusBrushConverter : IValueConverter
{
    private static readonly SolidColorBrush Done = new(Color.FromArgb(255, 0x37, 0xD3, 0x9A));
    private static readonly SolidColorBrush Failed = new(Color.FromArgb(255, 0xFF, 0x5F, 0x6D));
    private static readonly SolidColorBrush Warn = new(Color.FromArgb(255, 0xFF, 0xB4, 0x54));
    private static readonly SolidColorBrush Accent = new(Color.FromArgb(255, 0x4C, 0x8D, 0xFF));
    private static readonly SolidColorBrush Muted = new(Color.FromArgb(255, 0x98, 0xA1, 0xB3));

    public object Convert(object value, Type targetType, object parameter, string language)
        => value is JobStatus status
            ? status switch
            {
                JobStatus.Done => Done,
                JobStatus.Failed => Failed,
                JobStatus.Skipped => Warn,
                JobStatus.Running => Accent,
                JobStatus.Canceled => Muted,
                _ => Muted,
            }
            : (object)Muted;

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => throw new NotSupportedException();
}
