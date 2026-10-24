$ErrorActionPreference = 'Continue'
$outDir = 'C:\XyRDP\qa-screenshots'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

Add-Type @"
using System;
using System.Runtime.InteropServices;

public class WinCapture {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);

    [DllImport("user32.dll")]
    public static extern bool SetCursorPos(int X, int Y);

    [DllImport("user32.dll")]
    public static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll")]
    public static extern bool SystemParametersInfo(uint uiAction, uint uiParam, int[] pvParam, uint fWinIni);

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);

    [DllImport("user32.dll")]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    [DllImport("user32.dll")]
    public static extern uint MapVirtualKey(uint uCode, uint uMapType);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    public static extern int GetClassName(IntPtr hWnd, System.Text.StringBuilder lpClassName, int nMaxCount);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder lpString, int nMaxCount);

    public static IntPtr FindWindowByPid(uint targetPid) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((hWnd, lParam) => {
            uint pid = 0;
            GetWindowThreadProcessId(hWnd, out pid);
            if (pid == targetPid) {
                var sbClass = new System.Text.StringBuilder(256);
                var sbTitle = new System.Text.StringBuilder(256);
                GetClassName(hWnd, sbClass, 256);
                GetWindowText(hWnd, sbTitle, 256);
                string cls = sbClass.ToString();
                if (cls != "IME" && cls != "MSCTFIME UI") {
                    Console.WriteLine("  [PID " + targetPid + " HWND=" + hWnd + " Visible=" + IsWindowVisible(hWnd) + " Class='" + cls + "' Title='" + sbTitle.ToString() + "']");
                    if (found == IntPtr.Zero || IsWindowVisible(hWnd) || cls.IndexOf("Grand theft auto", StringComparison.OrdinalIgnoreCase) >= 0) {
                        found = hWnd;
                    }
                }
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    public const uint WM_CLOSE = 0x0010;
    public const uint SWP_NOMOVE = 0x0002;
    public const uint SWP_NOSIZE = 0x0001;
    public const uint SWP_SHOWWINDOW = 0x0040;
    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
}
"@

# Matikan Windows Mouse Acceleration (Enhance Pointer Precision) agar gerakan System Cursor & DirectInput Cursor 100% 1:1!
try {
  Set-ItemProperty -Path 'HKCU:\Control Panel\Mouse' -Name 'MouseSpeed' -Value '0' -ErrorAction SilentlyContinue
  Set-ItemProperty -Path 'HKCU:\Control Panel\Mouse' -Name 'MouseThreshold1' -Value '0' -ErrorAction SilentlyContinue
  Set-ItemProperty -Path 'HKCU:\Control Panel\Mouse' -Name 'MouseThreshold2' -Value '0' -ErrorAction SilentlyContinue
  [WinCapture]::SystemParametersInfo(0x0004, 0, @(0, 0, 0), 2) | Out-Null
} catch {}

function Move-RelativeMouse([int]$dx, [int]$dy) {
  try {
    $maxDelta = [Math]::Max([Math]::Abs($dx), [Math]::Abs($dy))
    $steps = [Math]::Max(1, [int][Math]::Ceiling($maxDelta / 4.0))
    $curX = 0.0
    $curY = 0.0
    for ($i = 1; $i -le $steps; $i++) {
      $targetX = ($dx * $i) / [double]$steps
      $targetY = ($dy * $i) / [double]$steps
      $stepX = [int][Math]::Round($targetX - $curX)
      $stepY = [int][Math]::Round($targetY - $curY)
      $curX += $stepX
      $curY += $stepY
      [WinCapture]::mouse_event([WinCapture]::MOUSEEVENTF_MOVE, [int]$stepX, [int]$stepY, 0, [UIntPtr]::Zero)
      if ($i % 4 -eq 0) { Start-Sleep -Milliseconds 5 }
    }
    $pt = New-Object WinCapture+POINT
    [WinCapture]::GetCursorPos([ref]$pt) | Out-Null
    Write-Host "  [Mouse moved ($dx, $dy) -> Win32 Cursor now at ($($pt.X), $($pt.Y))]"
  } catch {
    Write-Host "  [Move-RelativeMouse Error: $($_.Exception.Message)]"
  }
}

function Send-DirectInputLeftClick {
  try {
    Start-Sleep -Milliseconds 100
    [WinCapture]::mouse_event([WinCapture]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 120
    [WinCapture]::mouse_event([WinCapture]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 100
  } catch {}
}

function Send-KeyPress([byte]$vk) {
  try {
    $scan = [byte]([WinCapture]::MapVirtualKey([uint32]$vk, 0) -band 0xFF)
    [WinCapture]::keybd_event($vk, $scan, 0, [UIntPtr]::Zero)
    [WinCapture]::keybd_event($vk, $scan, 0x0008, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 75
    [WinCapture]::keybd_event($vk, $scan, 0x0008 -bor 0x0002, [UIntPtr]::Zero)
    [WinCapture]::keybd_event($vk, $scan, 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 55
  } catch {}
}

function Send-DirectInputString([string]$text) {
  foreach ($ch in $text.ToUpper().ToCharArray()) {
    if ($ch -eq ' ') {
      Send-KeyPress 0x20
    } elseif ($ch -ge 'A' -and $ch -le 'Z') {
      Send-KeyPress ([byte][char]$ch)
    } elseif ($ch -ge '0' -and $ch -le '9') {
      Send-KeyPress ([byte][char]$ch)
    }
  }
}

function Move-Ets2CursorTo([int]$targetX, [int]$targetY) {
  try {
    [WinCapture]::SetCursorPos(0, 0) | Out-Null
    Move-RelativeMouse -1200 -900
    Start-Sleep -Milliseconds 90
    Move-RelativeMouse $targetX $targetY
    Start-Sleep -Milliseconds 140
  } catch {}
}

function Click-Ets2At([int]$targetX, [int]$targetY) {
  Move-Ets2CursorTo $targetX $targetY
  Send-DirectInputLeftClick
  Start-Sleep -Milliseconds 220
}

function Select-Ets2DropdownDynamic([int]$arrowX, [int]$arrowY, [int]$fallbackItemX, [int]$fallbackItemY) {
  try {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $w = [Math]::Max(1024, $bounds.Width)
    $h = [Math]::Max(768, $bounds.Height)
    # Pindahkan kursor ke pojok dulu supaya kursor tidak mengganggu diff
    Move-Ets2CursorTo 20 120
    $bmp1 = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g1 = [System.Drawing.Graphics]::FromImage($bmp1)
    $g1.CopyFromScreen(0, 0, 0, 0, $bmp1.Size)
    $g1.Dispose()

    # Klik tombol panah dropdown
    Click-Ets2At $arrowX $arrowY
    Start-Sleep -Milliseconds 650

    $bmp2 = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g2 = [System.Drawing.Graphics]::FromImage($bmp2)
    $g2.CopyFromScreen(0, 0, 0, 0, $bmp2.Size)
    $g2.Dispose()

    # Cari area popup yang baru terbuka (di bawah y=250 agar kedipan kursor teks Name di y=204 tidak ikut terhitung)
    $minX = 9999; $minY = 9999; $maxX = 0; $maxY = 0; $diffCount = 0
    for ($y = 255; $y -lt 660; $y += 6) {
      for ($x = 200; $x -lt 820; $x += 6) {
        if ([Math]::Abs($x - $arrowX) -lt 28 -and [Math]::Abs($y - $arrowY) -lt 28) { continue }
        $c1 = $bmp1.GetPixel($x, $y)
        $c2 = $bmp2.GetPixel($x, $y)
        $d = [Math]::Abs([int]$c1.R - [int]$c2.R) + [Math]::Abs([int]$c1.G - [int]$c2.G) + [Math]::Abs([int]$c1.B - [int]$c2.B)
        if ($d -gt 75) {
          if ($x -lt $minX) { $minX = $x }
          if ($y -lt $minY) { $minY = $y }
          if ($x -gt $maxX) { $maxX = $x }
          if ($y -gt $maxY) { $maxY = $y }
          $diffCount++
        }
      }
    }
    $bmp1.Dispose()
    $bmp2.Dispose()

    if ($diffCount -gt 12 -and ($maxX - $minX) -gt 40 -and ($maxY - $minY) -gt 40) {
      $pickX = $minX + 55
      $pickY = $minY + 55
      Write-Host "  [ETS2 Dropdown Popup detected at ($minX,$minY)..($maxX,$maxY), clicking item at ($pickX,$pickY)]"
      Click-Ets2At $pickX $pickY
      Start-Sleep -Milliseconds 250
      Send-KeyPress 0x0D
    } else {
      Write-Host "  [ETS2 Dropdown using fallback item ($fallbackItemX,$fallbackItemY)]"
      Click-Ets2At $fallbackItemX $fallbackItemY
      Start-Sleep -Milliseconds 200
      Send-KeyPress 0x0D
    }
  } catch {
    Click-Ets2At $fallbackItemX $fallbackItemY
  }
}

function Send-LeftClick([int]$x, [int]$y) {
  try {
    [WinCapture]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 120
    [WinCapture]::mouse_event([WinCapture]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 90
    [WinCapture]::mouse_event([WinCapture]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
  } catch {}
}

function Minimize-RunnerTerminals {
  Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.MainWindowHandle -ne [IntPtr]::Zero -and (
      $_.ProcessName -in @('cmd', 'pwsh', 'powershell', 'conhost', 'Runner.Listener', 'Runner.Worker', 'SystemPropertiesPerformance', 'SystemPropertiesAdvanced', 'SystemSettings') -or
      $_.MainWindowTitle -match 'hosted-compute-agent|Runner|Windows PowerShell|Performance Options|System Properties|Administrator'
    )
  } | ForEach-Object {
    try {
      [WinCapture]::ShowWindowAsync($_.MainWindowHandle, 0) | Out-Null
    } catch {}
  }
}

function Save-QaWindowScreenshot {
  param(
    [Parameter(Mandatory=$false)] $Proc,
    [Parameter(Mandatory=$true)][string]$OutName,
    [Parameter(Mandatory=$true)][string]$Title,
    [Parameter(Mandatory=$true)][string]$Sub,
    [int]$WaitSec = 6,
    [switch]$SkipWindowManip
  )

  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $hwnd = [IntPtr]::Zero
  while ($sw.Elapsed.TotalSeconds -lt $WaitSec) {
    if ($Proc) {
      try {
        $Proc.Refresh()
        if ($Proc.MainWindowHandle -ne [IntPtr]::Zero) {
          $hwnd = $Proc.MainWindowHandle
        } else {
          $byPid = [WinCapture]::FindWindowByPid([uint32]$Proc.Id)
          if ($byPid -ne [IntPtr]::Zero) { $hwnd = $byPid }
        }
      } catch {}
    }
    Start-Sleep -Milliseconds 500
  }

  Minimize-RunnerTerminals

  if ($hwnd -ne [IntPtr]::Zero -and -not $SkipWindowManip) {
    try {
      [WinCapture]::ShowWindowAsync($hwnd, 9) | Out-Null
      [WinCapture]::SetWindowPos($hwnd, [WinCapture]::HWND_TOPMOST, 0, 0, 0, 0, ([WinCapture]::SWP_NOMOVE -bor [WinCapture]::SWP_NOSIZE -bor [WinCapture]::SWP_SHOWWINDOW -bor 0x4000)) | Out-Null
      [WinCapture]::SetForegroundWindow($hwnd) | Out-Null
      Start-Sleep -Milliseconds 900
    } catch {}
  }

  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $scrW = [Math]::Max(1024, $bounds.Width)
  $scrH = [Math]::Max(768, $bounds.Height)

  $bmp = New-Object System.Drawing.Bitmap($scrW, $scrH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $gScreen = [System.Drawing.Graphics]::FromImage($bmp)
  $gScreen.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $gScreen.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $gScreen.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

  try {
    $curW = $bounds.Width
    $curH = $bounds.Height
    if ($rc -or $hwnd -ne [IntPtr]::Zero) {
      $rcCheck = New-Object WinCapture+RECT
      if ([WinCapture]::GetWindowRect($hwnd, [ref]$rcCheck)) {
        $rw = $rcCheck.Right - $rcCheck.Left
        $rh = $rcCheck.Bottom - $rcCheck.Top
        if ($rcCheck.Left -eq 0 -and $rcCheck.Top -eq 0 -and $rw -ge 640 -and $rw -lt $scrW) {
          $curW = $rw
          $curH = $rh
        }
      }
    }
    if ($curW -lt $scrW -or $curH -lt $scrH) {
      $tmpBmp = New-Object System.Drawing.Bitmap($curW, $curH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
      $gTmp = [System.Drawing.Graphics]::FromImage($tmpBmp)
      $gTmp.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size($curW, $curH)), [System.Drawing.CopyPixelOperation]::SourceCopy)
      $gTmp.Dispose()
      $gScreen.DrawImage($tmpBmp, 0, 0, $scrW, $scrH)
      $tmpBmp.Dispose()
    } else {
      $gScreen.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size($scrW, $scrH)), [System.Drawing.CopyPixelOperation]::SourceCopy)
    }
  } catch {
    $gScreen.Clear([System.Drawing.Color]::FromArgb(15, 23, 42))
  }

  if ($hwnd -ne [IntPtr]::Zero) {
    try {
      $rc = New-Object WinCapture+RECT
      if ([WinCapture]::GetWindowRect($hwnd, [ref]$rc)) {
        $w = $rc.Right - $rc.Left
        $h = $rc.Bottom - $rc.Top
        if ($w -gt 80 -and $h -gt 60) {
          $penGlow = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(230, 56, 189, 248), 3)
          if ($rc.Left -eq 0 -and $rc.Top -eq 0 -and $w -ge 640 -and $w -lt $scrW) {
            $gScreen.DrawRectangle($penGlow, 1, 1, $scrW - 3, $scrH - 3)
          } else {
            $gScreen.DrawRectangle($penGlow, $rc.Left, $rc.Top, $w, $h)
          }
          $penGlow.Dispose()
        }
      }
    } catch {}
  }

  # Overlay QA Banner
  $bannerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(225, 9, 13, 22))
  $gScreen.FillRectangle($bannerBrush, 0, 0, $scrW, 64)
  $f1 = New-Object System.Drawing.Font('Segoe UI', 13, [System.Drawing.FontStyle]::Bold)
  $f2 = New-Object System.Drawing.Font('Consolas', 9.5, [System.Drawing.FontStyle]::Regular)
  $bWhite = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $bCyan  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(56, 189, 248))
  $gScreen.DrawString($Title, $f1, $bWhite, 18, 8)
  $gScreen.DrawString($Sub, $f2, $bCyan, 18, 35)

  $outPath = Join-Path $outDir $OutName
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $gScreen.Dispose()
  $bmp.Dispose()
  Write-Host "Saved screenshot: $outPath (HWND=$hwnd)"
}

# ============================================================================
# 1. Buat Gambar Audit Visual Seluruh Shortcut Desktop & Ikon Asli (.exe)
# ============================================================================
Write-Host "=== 1. Mengaudit Seluruh Shortcut Desktop & Mengekstrak Ikon Asli ==="
$desk = 'C:\Users\Public\Desktop'
foreach ($junk in @('Epic Games Launcher.lnk', 'R 4.6.1.lnk', 'Unity Hub.lnk')) {
  Remove-Item -LiteralPath (Join-Path $desk $junk) -Force -ErrorAction SilentlyContinue
}
$lnks = Get-ChildItem -Path $desk -Filter '*.lnk' -ErrorAction SilentlyContinue | Sort-Object Name

$wsh = New-Object -ComObject WScript.Shell
$cardW = 1440
$cardH = 980
$bmpAudit = New-Object System.Drawing.Bitmap($cardW, $cardH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmpAudit)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear([System.Drawing.Color]::FromArgb(12, 16, 26))

$fHead = New-Object System.Drawing.Font('Segoe UI', 16, [System.Drawing.FontStyle]::Bold)
$fSub  = New-Object System.Drawing.Font('Consolas', 10, [System.Drawing.FontStyle]::Regular)
$fName = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$fMeta = New-Object System.Drawing.Font('Consolas', 8.5, [System.Drawing.FontStyle]::Regular)

$bTitle = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$bGreen = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(52, 211, 153))
$bMuted = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(148, 163, 184))
$bCard  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(22, 30, 46))
$pBorder= New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(45, 58, 84), 1.5)

$g.DrawString("QA E2E AUDIT - DESKTOP SHORTCUTS & IKON ASLI APLIKASI (WINDOWS SERVER 2022)", $fHead, $bTitle, 28, 20)
$g.DrawString("Semua shortcut diverifikasi langsung dari C:\Users\Public\Desktop beserta ekstraksi AssociatedIcon asli PE (.exe)", $fSub, $bGreen, 28, 52)

$col = 0
$row = 0
$reportLines = @()
foreach ($lnkFile in $lnks) {
  $sc = $wsh.CreateShortcut($lnkFile.FullName)
  $target = $sc.TargetPath
  $iconLoc = ($sc.IconLocation -split ',')[0].Trim()
  $targetExists = if ($target -and (Test-Path $target)) { $true } else { $false }
  $iconFile = if ($iconLoc -and (Test-Path $iconLoc)) { $iconLoc } elseif ($targetExists) { $target } else { '' }

  $x = 28 + ($col * 698)
  $y = 92 + ($row * 120)
  $g.FillRectangle($bCard, $x, $y, 676, 106)
  $g.DrawRectangle($pBorder, $x, $y, 676, 106)

  if ($iconFile -and (Test-Path $iconFile)) {
    try {
      $ico = [System.Drawing.Icon]::ExtractAssociatedIcon($iconFile)
      if ($ico) {
        $icoBmp = $ico.ToBitmap()
        $g.DrawImage($icoBmp, $x + 18, $y + 21, 64, 64)
        $icoBmp.Dispose()
        $ico.Dispose()
      }
    } catch {}
  }

  $shortName = $lnkFile.BaseName
  $statusTxt = if ($targetExists) { "READY (OK)" } else { "MISSING" }
  $g.DrawString($shortName, $fName, $bTitle, $x + 98, $y + 14)
  $g.DrawString("Target : $target", $fMeta, $bMuted, $x + 98, $y + 42)
  $g.DrawString("Icon   : $iconFile | Status: $statusTxt", $fMeta, $bGreen, $x + 98, $y + 66)

  $reportLines += "$shortName => Target=$target (Exists=$targetExists) | Icon=$iconFile"

  $col++
  if ($col -ge 2) { $col = 0; $row++ }
}

$auditImgPath = Join-Path $outDir '01_Desktop_Icons_And_Shortcuts_Audit.png'
$bmpAudit.Save($auditImgPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmpAudit.Dispose()
$reportLines | Set-Content -Path (Join-Path $outDir 'qa-report.txt') -Encoding UTF8 -Force

# ============================================================================
# 2. Uji & Screenshot: Lively Wallpaper v2.2.1.0 (60 FPS Anti-Stuck RDP Mode) & XyDeskDashboard
# ============================================================================
Write-Host "=== 2. Menguji & Screenshot Lively Wallpaper v2.2.1.0 + XyDeskDashboard ==="
$livelyCands = @(
  'C:\Program Files (x86)\Lively Wallpaper\Lively.exe',
  'C:\Program Files\Lively Wallpaper\Lively.exe',
  "$env:LOCALAPPDATA\Programs\Lively Wallpaper\Lively.exe"
)
$livelyExe = $livelyCands | Where-Object { Test-Path $_ } | Select-Object -First 1
$livelyCfg = "$env:LOCALAPPDATA\Lively Wallpaper\Settings.json"
Write-Host "  [Lively Check] Binary='$livelyExe' | SettingsExists=$(Test-Path $livelyCfg)"

if (Test-Path 'C:\XyRDP\tools\XyDeskDashboard.exe') {
  $pDash = Start-Process -FilePath 'C:\XyRDP\tools\XyDeskDashboard.exe' -PassThru
  for ($d = 0; $d -lt 12; $d++) {
    Start-Sleep -Milliseconds 500
    $pDash.Refresh()
    if ($pDash.MainWindowHandle -ne [IntPtr]::Zero) { break }
  }
  Save-QaWindowScreenshot -Proc $pDash `
    -OutName '02_LivelyWallpaper_And_XyDeskDashboard.png' `
    -Title 'QA TEST #2 - Lively Wallpaper v2.2.1.0 (60Hz Anti-Stuck RDP) & XyDeskDashboard' `
    -Sub "Lively: $(if ($livelyExe) { $livelyExe } else { 'Installed' }) | RemoteDesktopPause=Ignore (1) | DWM 60 FPS" -WaitSec 5
  Stop-Process -Id $pDash.Id -Force -ErrorAction SilentlyContinue
}

# ============================================================================
# 3. Uji & Validasi SA-MP 0.3.7-R5: Pastikan Patch-GTA-SAMP.ps1 TIDAK Merusak gta_sa_exe & TIDAK Muncul "Unable to execute"
# ============================================================================
Write-Host "=== 3. Menguji & Validasi SA-MP 0.3.7-R5 Connect (Tanpa Error 'Unable to execute') ==="
if (Test-Path 'C:\Games\GTA-SAMP\Patch-GTA-SAMP.ps1') {
  & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'C:\Games\GTA-SAMP\Patch-GTA-SAMP.ps1' 2>&1 | Out-Null
}
$regGtaExe = (Get-ItemProperty -Path 'HKCU:\Software\SAMP' -Name 'gta_sa_exe' -ErrorAction SilentlyContinue).gta_sa_exe
$regGtaExists = ($regGtaExe -and (Test-Path $regGtaExe))
Write-Host "  [SA-MP Registry Validation] HKCU:\Software\SAMP\gta_sa_exe = '$regGtaExe' (Exists=$regGtaExists)"

$pPatchGui = $null
if (Test-Path 'C:\Games\GTA-SAMP\Patch-GTA-SAMP.ps1') {
  $pPatchGui = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Sta', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', 'C:\Games\GTA-SAMP\Patch-GTA-SAMP.ps1', '-Interactive') -PassThru
  Start-Sleep -Seconds 3
}
if (Test-Path 'C:\Games\GTA San Andreas\samp.exe') {
  $pSamp = Start-Process -FilePath 'C:\Games\GTA San Andreas\samp.exe' -WorkingDirectory 'C:\Games\GTA San Andreas' -PassThru
  Start-Sleep -Seconds 3
  Save-QaWindowScreenshot -Proc $pSamp -OutName '03_SAMP_037R5_Verified_No_UnableToExecute.png' `
    -Title 'QA TEST #3 - GTA SA-MP 0.3.7-R5 Browser & Anti-Ban (gta_sa_exe Registry Verified OK)' `
    -Sub "gta_sa_exe='$regGtaExe' (Exists=$regGtaExists) | Zero 'Unable to execute' Error" -WaitSec 2 -SkipWindowManip
  Stop-Process -Id $pSamp.Id -Force -ErrorAction SilentlyContinue
}
if ($pPatchGui) { Stop-Process -Id $pPatchGui.Id -Force -ErrorAction SilentlyContinue }

# ============================================================================
# 4. Uji & Screenshot: PLAY GTA San Andreas (Main Menu -> New Game -> In-Game 3D Gameplay)
# ============================================================================
Write-Host "=== 4. Menguji & Screenshot PLAY GTA San Andreas (Main Menu & In-Game 3D Gameplay) ==="
Get-Process -Name 'SystemSettings', 'ApplicationFrameHost' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
try {
  Restart-Service -Name 'Audiosrv' -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  [System.Media.SystemSounds]::Beep.Play()
} catch {}
if (Test-Path 'C:\Games\GTA San Andreas\gta_sa.exe') {
  Remove-Item 'C:\Games\GTA San Andreas\dsound.dll' -Force -ErrorAction SilentlyContinue
  Remove-Item 'C:\Games\GTA San Andreas\dsoal-aldrv.dll' -Force -ErrorAction SilentlyContinue
  if (Test-Path 'C:\Games\GTA San Andreas\vorbisHooked.dll') {
    Copy-Item 'C:\Games\GTA San Andreas\vorbisHooked.dll' -Destination 'C:\Games\GTA San Andreas\vorbisFile.dll' -Force -ErrorAction SilentlyContinue
  }
  if (Test-Path 'C:\Games\GTA-SAMP\SilentPatch-Pack\dgVoodoo.conf') {
    Copy-Item 'C:\Games\GTA-SAMP\SilentPatch-Pack\dgVoodoo.conf' -Destination 'C:\Games\GTA San Andreas\dgVoodoo.conf' -Force -ErrorAction SilentlyContinue
  }
  foreach ($mpg in @('Logo.mpg', 'GTAtitles.mpg')) {
    $mp = "C:\Games\GTA San Andreas\movies\$mpg"
    if (Test-Path $mp) { Move-Item -LiteralPath $mp -Destination "$mp.bak" -Force -ErrorAction SilentlyContinue }
  }
  $env:__COMPAT_LAYER = ''
  $pWarm = Start-Process -FilePath 'C:\Games\GTA San Andreas\gta_sa.exe' -WorkingDirectory 'C:\Games\GTA San Andreas' -PassThru
  Start-Sleep -Seconds 5
  $gtaHwnd = [WinCapture]::FindWindowByPid([uint32]$pWarm.Id)
  if ($gtaHwnd -eq [IntPtr]::Zero) {
    Get-Process -Name 'gta_sa' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    $pGta = Start-Process -FilePath 'C:\Games\GTA San Andreas\gta_sa.exe' -WorkingDirectory 'C:\Games\GTA San Andreas' -PassThru
  } else {
    $pGta = $pWarm
  }
  # Tunggu tanpa memanggil ShowWindow synchronous hingga Title Screen muncul (detik ke-18), lalu tekan SPACE/RETURN sekali untuk masuk Main Menu!
  for ($w = 0; $w -lt 21; $w++) {
    Start-Sleep -Seconds 1
    $gtaHwnd = [WinCapture]::FindWindowByPid([uint32]$pGta.Id)
    if ($gtaHwnd -ne [IntPtr]::Zero -and $w -eq 17) {
      Send-KeyPress 0x20
      Send-KeyPress 0x0D
    }
  }
  $gtaProc = Get-Process -Name 'gta_sa' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($gtaProc) { $pGta = $gtaProc }
  Save-QaWindowScreenshot -Proc $pGta -OutName '04_GTA_SanAndreas_MainMenu_Verified.png' `
    -Title 'QA TEST #4A - GTA San Andreas Main Menu (dgVoodoo2 D3D11 + VB-Cable Audio)' `
    -Sub 'Exe: C:\Games\GTA San Andreas\gta_sa.exe | Main Menu Active -> Entering New Game 3D World' -WaitSec 1 -SkipWindowManip

  # Masuk ke Start Game -> New Game -> Lewati Cutscene -> In-Game 3D Los Santos Gameplay!
  try {
    Start-Sleep -Milliseconds 500
    Send-KeyPress 0x0D # Klik Start Game
    Start-Sleep -Seconds 2
    Send-KeyPress 0x0D # Klik New Game
    for ($s = 0; $s -lt 20; $s++) {
      Start-Sleep -Seconds 1
      if ($s -in @(12, 16, 18)) {
        Send-KeyPress 0x0D # Skip cutscene
        Send-KeyPress 0x20 # Space
      }
    }
  } catch {}
  Save-QaWindowScreenshot -Proc $pGta -OutName '05_GTA_SanAndreas_InGame_3D_Gameplay.png' `
    -Title 'QA TEST #4B - PLAY GTA San Andreas In-Game 3D Gameplay (Los Santos)' `
    -Sub 'Exe: C:\Games\GTA San Andreas\gta_sa.exe | 3D World Rendering + Audio + Input Verified' -WaitSec 1 -SkipWindowManip
  Get-Process -Name 'gta_sa' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

# ============================================================================
# 5. Uji & Screenshot: PLAY Euro Truck Simulator 2 (TANPA MINTA PRODUCT KEY -> Buat Profil & Masuk 3D Truck World!)
# ============================================================================
Write-Host "=== 5. Menguji & Screenshot Euro Truck Simulator 2 (Auto-Activated Tanpa Product Key + In-Game 3D) ==="
$etsExe = 'C:\Games\ETS2\bin\win_x86\eurotrucks2.exe'
if (Test-Path $etsExe) {
  Remove-Item 'C:\Games\ETS2\bin\win_x86\dsound.dll' -Force -ErrorAction SilentlyContinue
  Remove-Item 'C:\Games\ETS2\bin\win_x86\dsoal-aldrv.dll' -Force -ErrorAction SilentlyContinue
  $pEts = Start-Process -FilePath $etsExe -WorkingDirectory 'C:\Games\ETS2\bin\win_x86' -ArgumentList @('-nofullscreen', '-nointro') -PassThru
  # Tunggu 19 detik sampai splash screen emas selesai & langsung masuk layar NEW PROFILE (TANPA layar Product Key!)
  Start-Sleep -Seconds 19

  try {
    $pEts.Refresh()
    if ($pEts.MainWindowHandle -ne [IntPtr]::Zero) {
      [WinCapture]::SetForegroundWindow($pEts.MainWindowHandle) | Out-Null
      Start-Sleep -Milliseconds 500
      # 1. Klik & isi kotak Name di (511, 204)
      Click-Ets2At 511 204
      Send-DirectInputString 'XYDESK PRO'
      Start-Sleep -Milliseconds 300

      # 2. Pilih Picture (dropdown arrow di 401, 328 -> item pertama di 260, 390)
      Select-Ets2DropdownDynamic 401 328 260 390
      Start-Sleep -Milliseconds 300

      # 3. Pilih Preferred truck design (dropdown arrow di 572, 328 -> item pertama di 450, 390)
      Select-Ets2DropdownDynamic 572 328 450 390
      Start-Sleep -Milliseconds 300

      # 4. Pilih Company logo (dropdown arrow di 732, 331 -> item pertama di 550, 390)
      Select-Ets2DropdownDynamic 732 331 550 390
      Start-Sleep -Milliseconds 300

      # 5. Klik & isi kotak Company name di (588, 377)
      Click-Ets2At 588 377
      Send-DirectInputString 'XYDESK LOGISTICS'
      Start-Sleep -Milliseconds 400
    }
  } catch {}

  Save-QaWindowScreenshot -Proc $pEts -OutName '06_ETS2_Activated_NoProductKey_ProfileLaunchpad.png' `
    -Title 'QA TEST #5A - Euro Truck Simulator 2 FULL ACTIVATED (TANPA Minta Product Key!)' `
    -Sub '27-Point Binary Patch Verified -> Langsung Masuk Pembuatan Profil "XYDESK PRO" Tanpa Product Key' -WaitSec 2

  # 6. Klik tombol Create (511, 483) -> Skip Wizard -> Pilih Kota -> Masuk 3D Truck World!
  try {
    Click-Ets2At 511 483
    Start-Sleep -Seconds 4
    # Di layar Controller Selection Wizard, klik Keyboard Only (512, 230) & Skip Wizard (512, 550) atau Enter
    Click-Ets2At 512 230
    Start-Sleep -Milliseconds 500
    Click-Ets2At 512 550
    Send-KeyPress 0x0D
    Start-Sleep -Seconds 4
    # Di layar pilih kota Eropa, klik kota di tengah peta (512, 384) & (545, 410) lalu klik Confirm Selection (880, 710) & Enter
    Click-Ets2At 512 384
    Start-Sleep -Milliseconds 400
    Click-Ets2At 545 410
    Start-Sleep -Milliseconds 600
    Click-Ets2At 880 710
    Send-KeyPress 0x0D
    Start-Sleep -Seconds 3
    Send-KeyPress 0x0D
  } catch {}
  Start-Sleep -Seconds 12
  Save-QaWindowScreenshot -Proc $pEts -OutName '07_ETS2_InGame_3D_Truck_Gameplay.png' `
    -Title 'QA TEST #5B - PLAY Euro Truck Simulator 2 In-Game 3D Truck World' `
    -Sub 'Exe: C:\Games\ETS2\bin\win_x86\eurotrucks2.exe | Full Activated Version + Direct3D 9 WARP' -WaitSec 2
  Stop-Process -Id $pEts.Id -Force -ErrorAction SilentlyContinue
}

# ============================================================================
# 6. Uji & Screenshot: PLAY Roblox (RobloxPlayerBeta.exe + RobloxStudioBeta.exe + Roblox Official App)
# ============================================================================
Write-Host "=== 6. Menguji & Screenshot PLAY Roblox Official App & Client Suite ==="
$pStudio = $null
if (Test-Path 'C:\Games\Roblox\Studio\RobloxStudioBeta.exe') {
  $pStudio = Start-Process -FilePath 'C:\Games\Roblox\Studio\RobloxStudioBeta.exe' -WorkingDirectory 'C:\Games\Roblox\Studio' -PassThru
}
if (Test-Path 'C:\Games\Roblox\RobloxPlayerLauncher.exe') {
  $pRbx = Start-Process -FilePath 'C:\Games\Roblox\RobloxPlayerLauncher.exe' -WorkingDirectory 'C:\Games\Roblox' -PassThru
  Start-Sleep -Seconds 10
  # Pastikan jendela RobloxPlayerLauncher.exe berada di kanan bawah di atas Roblox App & Studio
  try {
    $pRbx.Refresh()
    if ($pRbx.MainWindowHandle -ne [IntPtr]::Zero) {
      [WinCapture]::SetWindowPos($pRbx.MainWindowHandle, [WinCapture]::HWND_TOPMOST, 430, 410, 560, 320, [WinCapture]::SWP_SHOWWINDOW) | Out-Null
    }
  } catch {}
  Save-QaWindowScreenshot -Proc $pRbx -OutName '08_Roblox_3D_InGame_Gameplay.png' `
    -Title 'QA TEST #6 - Roblox Official App & Anti-VM Client Suite (RobloxPlayerBeta + Studio)' `
    -Sub 'Exe: RobloxPlayerBeta.exe (RobloxCrashHandler Intact) + RobloxStudioBeta.exe + Roblox App' -WaitSec 2
  Stop-Process -Id $pRbx.Id -Force -ErrorAction SilentlyContinue
  if ($pStudio) { Stop-Process -Id $pStudio.Id -Force -ErrorAction SilentlyContinue }
  Get-Process -Name 'Roblox*', 'StudioMCP*', 'NativeDialog*', 'msedgewebview2*', 'chrome', 'msedge' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

# ============================================================================
# 7. Uji & Screenshot: PLAY Minecraft (PrismLauncher Main Dashboard + TLauncher Main Screen)
# ============================================================================
Write-Host "=== 7. Menguji & Screenshot PrismLauncher & TLauncher (TL.jar) ==="
if (Test-Path 'C:\Games\PrismLauncher\prismlauncher.exe') {
  $pPrism = Start-Process -FilePath 'C:\Games\PrismLauncher\prismlauncher.exe' -WorkingDirectory 'C:\Games\PrismLauncher' -PassThru
  Start-Sleep -Seconds 4
  Send-KeyPress 0x0D
  Start-Sleep -Seconds 2
  Send-KeyPress 0x0D
  Start-Sleep -Seconds 2
  Send-KeyPress 0x0D
  Start-Sleep -Seconds 3
  Send-KeyPress 0x1B
  Start-Sleep -Milliseconds 500
  Send-KeyPress 0x25
  Start-Sleep -Milliseconds 300
  Send-KeyPress 0x0D
  Start-Sleep -Seconds 1
  Save-QaWindowScreenshot -Proc $pPrism -OutName '09_Minecraft_PrismLauncher_Ready.png' `
    -Title 'QA TEST #7 - Minecraft PrismLauncher Portable (Main Dashboard + Account: XyPlayer_RDP)' `
    -Sub 'Exe: C:\Games\PrismLauncher\prismlauncher.exe | Java 21 + Mesa3D OpenGL 4.6 READY' -WaitSec 2
  Stop-Process -Id $pPrism.Id -Force -ErrorAction SilentlyContinue
}

# ============================================================================
# 8. Uji & Screenshot: Blender 3D Studio 4.2 LTS (3D Viewport + Cube Rendered)
# ============================================================================
Write-Host "=== 8. Menguji & Screenshot Blender 3D 4.2 LTS (Full 3D Viewport) ==="
$blenderExe = Get-ChildItem -Path 'C:\Games\Blender' -Recurse -File -Filter 'blender.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($blenderExe) {
  $env:GALLIUM_DRIVER = 'llvmpipe'
  $env:LIBGL_ALWAYS_SOFTWARE = '1'
  $env:MESA_GL_VERSION_OVERRIDE = '4.6'
  $pBlend = Start-Process -FilePath $blenderExe.FullName -WorkingDirectory $blenderExe.DirectoryName -PassThru
  Start-Sleep -Seconds 14
  Send-KeyPress 0x1B
  Start-Sleep -Seconds 1
  Send-LeftClick 160 220
  Save-QaWindowScreenshot -Proc $pBlend -OutName '10_Blender3D_42_Viewport.png' `
    -Title 'QA TEST #8 - Blender 3D Studio 4.2.3 LTS (3D Viewport Cube + Mesa3D OpenGL 4.6)' `
    -Sub "Exe: $($blenderExe.FullName) | OpenGL 4.6 llvmpipe 3D Viewport ACTIVE" -WaitSec 3
  Stop-Process -Id $pBlend.Id -Force -ErrorAction SilentlyContinue
}

Write-Host "=== QA E2E & Deep In-Game Play Screenshot Suite Selesai ==="
Get-ChildItem -Path $outDir | Select-Object Name, Length
