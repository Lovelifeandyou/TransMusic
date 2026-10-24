param(
    [string]$Match = "音频格式转换器",
    [string]$Out = "E:\transmusic\docs\shot.png"
)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public class WinShot {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder s, int n);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public static string Title(IntPtr h) {
        var sb = new StringBuilder(512);
        GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }

    public static string Class(IntPtr h) {
        var sb = new StringBuilder(256);
        GetClassName(h, sb, sb.Capacity);
        return sb.ToString();
    }

    /// 返回与给定矩形相交的其他可见顶层窗口
    public static List<IntPtr> Overlapping(IntPtr self, RECT area) {
        var result = new List<IntPtr>();
        EnumWindows((h, l) => {
            if (h == self || !IsWindowVisible(h)) return true;
            string cls = Class(h);
            if (cls == "Shell_TrayWnd" || cls == "Progman" || cls == "WorkerW") return true;
            RECT r;
            if (!GetWindowRect(h, out r)) return true;
            bool intersects = r.Left < area.Right && r.Right > area.Left && r.Top < area.Bottom && r.Bottom > area.Top;
            if (intersects) result.Add(h);
            return true;
        }, IntPtr.Zero);
        return result;
    }
}
"@

$target = [IntPtr]::Zero
for ($i = 0; $i -lt 40; $i++) {
    $p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "*$Match*" } | Select-Object -First 1
    if ($p) { $target = $p.MainWindowHandle; break }
    Start-Sleep -Milliseconds 500
}
if ($target -eq [IntPtr]::Zero) { Write-Output "no-window"; exit 1 }

$HWND_TOPMOST = [IntPtr](-1)
$SWP_NOMOVE = 0x0002; $SWP_NOSIZE = 0x0001

[WinShot]::ShowWindow($target, 9) | Out-Null      # SW_RESTORE

$rect = New-Object WinShot+RECT
[WinShot]::GetWindowRect($target, [ref]$rect) | Out-Null

# 1) 让与目标重叠的窗口临时最小化（记录以便还原）
$hidden = [WinShot]::Overlapping($target, $rect)
foreach ($h in $hidden) { [WinShot]::ShowWindow($h, 6) | Out-Null }   # SW_MINIMIZE
Start-Sleep -Milliseconds 900

# 2) 目标置顶并聚焦
[WinShot]::SetWindowPos($target, $HWND_TOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null
[WinShot]::SetForegroundWindow($target) | Out-Null
Start-Sleep -Milliseconds 1200

# 3) 抓取
[WinShot]::GetWindowRect($target, [ref]$rect) | Out-Null
$w = $rect.Right - $rect.Left
$h2 = $rect.Bottom - $rect.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h2
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bmp.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# 4) 还原被最小化的窗口
foreach ($h in $hidden) { [WinShot]::ShowWindow($h, 9) | Out-Null }
[WinShot]::SetWindowPos($target, [IntPtr](-2), 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null

Write-Output "saved $Out ${w}x${h2}（临时最小化 $($hidden.Count) 个遮挡窗口，已还原）"
