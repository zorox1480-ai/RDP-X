# ============================================================================
#  setup-discord.ps1 - Pasang Discord desktop di sesi RDP (best-effort)
# ----------------------------------------------------------------------------
#  Discord tidak punya installer per-mesin resmi; installer resminya memasang
#  per-pengguna ke %LocalAppData%\Discord. Karena itu:
#    1) unduh installer resmi Discord (channel stable, win x64)
#    2) jalankan senyap (/S) untuk pengguna setup saat ini
#    3) salin folder aplikasi + shortcut ke profil user RDP (bila profilnya
#       sudah ada) dan shortcut ke Public Desktop, supaya Discord langsung
#       bisa dibuka dari sesi RDP tanpa unduh ulang.
#  Gagal di langkah mana pun TIDAK mematikan sesi (continue-on-error di
#  workflow). Status ditulis ke out/rdp-status.json -> extras.discord.
# ============================================================================
$XyTag = 'XyRDP:discord'
. "$PSScriptRoot/lib-common.ps1"
$ErrorActionPreference = 'Continue'

$u = if ($env:RDP_USER) { $env:RDP_USER } else { 'xyadmin' }
$root = 'C:\ProgramData\RdpFree\discord'
New-Item -ItemType Directory -Path $root -Force | Out-Null

try {
  $installer = Join-Path $root 'DiscordSetup.exe'
  $url = 'https://discord.com/api/downloads/distributions/app/installers/latest?channel=stable&platform=win&arch=x64'
  if (-not (Get-File $url $installer 180)) { throw 'Unduhan installer Discord gagal' }

  $sig = Get-AuthenticodeSignature $installer
  if ($sig.Status -eq 'Valid') { Log "Signature Discord: $($sig.SignerCertificate.Subject)" }

  $proc = Start-Process $installer -ArgumentList '/S' -PassThru -WindowStyle Hidden
  if (-not $proc.WaitForExit(240000)) { $proc | Stop-Process -Force -ErrorAction SilentlyContinue; throw 'Installer Discord melewati timeout' }

  $srcApp = Join-Path $env:LocalAppData 'Discord'
  $srcExe = Get-ChildItem (Join-Path $srcApp 'app-*') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
  $exePath = if ($srcExe) { Join-Path $srcExe.FullName 'Discord.exe' } else { $null }
  if (-not $exePath -or -not (Test-Path $exePath)) { throw 'Discord.exe tidak ditemukan setelah install' }

  # Shortcut publik (semua user) -> target ke salinan per-user lewat launcher
  # Update.exe milik Discord (membuat ikon tetap valid setelah auto-update).
  $launcher = Join-Path $srcApp 'Update.exe'
  $publicDesktop = 'C:\Users\Public\Desktop'
  try {
    $wsh = New-Object -ComObject WScript.Shell
    $lnk = $wsh.CreateShortcut((Join-Path $publicDesktop 'Discord.lnk'))
    $lnk.TargetPath = $launcher
    $lnk.Arguments = '--processStart Discord.exe'
    $lnk.WorkingDirectory = $srcApp
    $lnk.IconLocation = "$exePath,0"
    $lnk.Save()
  } catch { Log "shortcut publik gagal: $($_.Exception.Message)" }

  # Salin ke profil user RDP bila profilnya sudah terbentuk.
  $dstApp = "C:\Users\$u\AppData\Local\Discord"
  if ((Test-Path "C:\Users\$u") -and -not (Test-Path $dstApp)) {
    try {
      Copy-Item $srcApp $dstApp -Recurse -Force -ErrorAction Stop
      Log "Discord disalin ke profil $u."
    } catch { Log "salin ke profil ${u} gagal: $($_.Exception.Message)" }
  }

  Update-Status @{ extras = @{ discord = 'ok' } } | Out-Null
  Log 'Discord siap (shortcut di Public Desktop).'
} catch {
  Update-Status @{ extras = @{ discord = 'failed'; discord_note = $_.Exception.Message } } | Out-Null
  Log "Discord dilewati: $($_.Exception.Message)"
}
