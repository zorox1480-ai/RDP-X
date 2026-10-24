# ============================================================================
#  restore-state.ps1 - Pulihkan State Sesi XyRDP dari GitHub Release
# ----------------------------------------------------------------------------
#  Mengunduh arsip terenkripsi dari Release `xyrdp-state-latest` di repo
#  pengguna, mendekripsi dengan AES-256-CBC (PBKDF2 dari RDP_PASSWORD /
#  GITHUB_TOKEN), lalu mengekstrak kembali ke:
#    - C:\Persist
#    - Profil resmi C:\Users\$u (Desktop, Documents, Downloads, Pictures, Videos, Music)
#    - Sesi Login Browser (Chrome, Edge, Brave, Firefox) + DPAPI Master Keys
#    - AppData\Roaming\.minecraft, .tlauncher, PrismLauncher, Roblox, & SAMP
#    - Wallpaper & Gaming Boost dari sesi sebelumnya
# ============================================================================

$XyTag = 'XyRDP:restore'
. "$PSScriptRoot/lib-common.ps1"

$cfg = Get-Cfg
if ($env:PERSIST_DATA -and @('tidak','0','false','no','off') -contains $env:PERSIST_DATA.Trim().ToLower()) {
  Log 'Pemulihan state dinonaktifkan oleh input pengguna - lewati.'
  exit 0
}

# Fallback baca environment dari C:\XyRDP\tools\session-env.json bila dipanggil dari Dashboard
if ((-not $env:GITHUB_TOKEN -or -not $env:GITHUB_REPOSITORY) -and (Test-Path 'C:\XyRDP\tools\session-env.json')) {
  try {
    $sEnv = Get-Content 'C:\XyRDP\tools\session-env.json' -Raw | ConvertFrom-Json
    if (-not $env:RDP_USER -and $sEnv.RDP_USER) { $env:RDP_USER = $sEnv.RDP_USER }
    if (-not $env:RDP_PASSWORD -and $sEnv.RDP_PASSWORD) { $env:RDP_PASSWORD = $sEnv.RDP_PASSWORD }
    if (-not $env:GITHUB_TOKEN -and $sEnv.GITHUB_TOKEN) { $env:GITHUB_TOKEN = $sEnv.GITHUB_TOKEN }
    if (-not $env:GITHUB_REPOSITORY -and $sEnv.GITHUB_REPOSITORY) { $env:GITHUB_REPOSITORY = $sEnv.GITHUB_REPOSITORY }
  } catch {}
}

$ghToken = $env:GITHUB_TOKEN
$ghRepo  = $env:GITHUB_REPOSITORY
$u = if ($env:RDP_USER) { $env:RDP_USER.Trim() } else { $cfg.rdp_user }
$p = if ($env:RDP_PASSWORD) { $env:RDP_PASSWORD.Trim() } else { '' }

# KRITIKAL: Pastikan profil Windows C:\Users\$u terdaftar resmi di ProfileList
# sebelum membuat subfolder apa pun di dalamnya!
$primaryProfDir = Ensure-UserProfileCreated $u

# Selalu siapkan C:\Persist dan C:\Games agar user langsung melihat folder permanen
New-Item -ItemType Directory -Path 'C:\Persist' -Force | Out-Null
New-Item -ItemType Directory -Path 'C:\Games' -Force | Out-Null

$readmePersist = 'C:\Persist\BACA-SAYA-ANTI-RESET.txt'
if (-not (Test-Path $readmePersist)) {
  @(
    '===================================================================='
    '  FOLDER PERMANEN XyRDP (C:\Persist) - ANTI RESET'
    '===================================================================='
    'Semua file, installer, akun browser, atau dokumen yang Anda simpan di:'
    '  1. C:\Persist'
    '  2. Desktop, Documents, Downloads, Pictures, Videos, Music'
    '  3. Google Chrome, Microsoft Edge, Brave, Firefox (Login & Cookie)'
    '  4. Save data Minecraft (.minecraft / PrismLauncher), Roblox & GTA SA'
    ''
    'Akan OTOMATIS DI-BACKUP setiap 4 menit & saat sesi RDP berakhir ke'
    'GitHub Release pribadi Anda (terenkripsi AES-256) dan DIPULIHKAN'
    'kembali saat Anda menyalakan RDP berikutnya!'
    '===================================================================='
  ) | Set-Content -Path $readmePersist -Encoding UTF8 -Force
}

if (-not $ghToken -or -not $ghRepo) {
  Log 'GITHUB_TOKEN atau GITHUB_REPOSITORY tidak tersedia - lewati restore state.'
  exit 0
}

$tag = 'xyrdp-state-latest'
$hdrs = @{
  'Authorization' = "Bearer $ghToken"
  'Accept'        = 'application/vnd.github+json'
  'User-Agent'    = 'XyRDP-State'
}

$rel = $null
try {
  $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$ghRepo/releases/tags/$tag" -Headers $hdrs -TimeoutSec 25
} catch {
  Log "Belum ada simpanan state sebelumnya di Release '$tag' (sesi pertama)."
  exit 0
}

$asset = $rel.assets | Where-Object { $_.name -like 'xyrdp-state.*.enc' } | Select-Object -First 1
$partAssets = @($rel.assets | Where-Object { $_.name -like 'xyrdp-state.*.enc.part*' } | Sort-Object -Property name)
if (-not $asset -and $partAssets.Count -eq 0) {
  Log 'Asset state tidak ditemukan di release.'
  exit 0
}

$workDir = 'C:\XyRDP\restore-work'
Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

$dlHdrs = @{
  'Authorization' = "Bearer $ghToken"
  'Accept'        = 'application/octet-stream'
  'User-Agent'    = 'XyRDP-State'
}

if ($partAssets.Count -gt 0) {
  $baseEncName = $partAssets[0].name -replace '\.part\d+$', ''
  $encPath = Join-Path $workDir $baseEncName
  $zipPath = Join-Path $workDir ($baseEncName -replace '\.enc$', '')
  $extDir  = Join-Path $workDir 'extracted'
  Log "Mengunduh & menggabungkan $($partAssets.Count) part arsip state ($baseEncName)..."
  try {
    $fsJoin = [System.IO.File]::Create($encPath)
    try {
      foreach ($pa in $partAssets) {
        $tmpPart = Join-Path $workDir $pa.name
        Invoke-WebRequest -Uri "https://api.github.com/repos/$ghRepo/releases/assets/$($pa.id)" -Headers $dlHdrs -OutFile $tmpPart -UseBasicParsing -TimeoutSec 240
        $fsPart = [System.IO.File]::OpenRead($tmpPart)
        $fsPart.CopyTo($fsJoin)
        $fsPart.Close()
        Remove-Item $tmpPart -Force -ErrorAction SilentlyContinue
      }
    } finally {
      $fsJoin.Close()
    }
  } catch {
    Log "Gagal mengunduh multi-part state: $($_.Exception.Message)"
    Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 0
  }
} else {
  $encPath = Join-Path $workDir $asset.name
  $zipPath = Join-Path $workDir ($asset.name -replace '\.enc$', '')
  $extDir  = Join-Path $workDir 'extracted'
  Log "Mengunduh state sesi sebelumnya ($($asset.name), $([math]::Round($asset.size / 1MB, 2)) MB)..."
  try {
    Invoke-WebRequest -Uri "https://api.github.com/repos/$ghRepo/releases/assets/$($asset.id)" -Headers $dlHdrs -OutFile $encPath -UseBasicParsing -TimeoutSec 240
  } catch {
    Log "Gagal mengunduh state: $($_.Exception.Message)"
    Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 0
  }
}

if (-not (Test-Path $encPath) -or (Get-Item $encPath).Length -lt 32) {
  Log 'File state yang diunduh kosong/rusak.'
  Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
  exit 0
}

# Coba dekripsi dengan key berbasis password saat ini, lalu fallback ke key berbasis repo
$keysToTry = @()
if ($p.Length -ge 4) { $keysToTry += "XyRDP-Persist-$u-$p" }
$keysToTry += "XyRDP-Persist-$ghRepo"

$decrypted = $false
foreach ($k in $keysToTry) {
  try {
    $fsIn = [System.IO.File]::OpenRead($encPath)
    $hdr = New-Object byte[] 6
    [void]$fsIn.Read($hdr, 0, 6)
    $magic = [System.Text.Encoding]::ASCII.GetString($hdr)
    if ($magic -ne 'XYST01') {
      $fsIn.Close()
      throw 'Header magic bukan XYST01'
    }
    $salt = New-Object byte[] 16
    [void]$fsIn.Read($salt, 0, 16)

    $kdf = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($k, $salt, 20000)
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256
    $aes.Key = $kdf.GetBytes(32)
    $aes.IV  = $kdf.GetBytes(16)
    $aes.Mode = [System.Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7

    $fsOut = [System.IO.File]::OpenWrite($zipPath)
    $cs = New-Object System.Security.Cryptography.CryptoStream($fsIn, $aes.CreateDecryptor(), [System.Security.Cryptography.CryptoStreamMode]::Read)
    $cs.CopyTo($fsOut)
    $fsOut.Close()
    $cs.Close()
    $fsIn.Close()
    $decrypted = $true
    break
  } catch {
    try { $fsOut.Close() } catch {}
    try { $fsIn.Close() } catch {}
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
  }
}

if (-not $decrypted -or -not (Test-Path $zipPath)) {
  Log 'Gagal mendekripsi arsip state (password RDP berubah dari sesi sebelumnya).'
  Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
  exit 0
}

New-Item -ItemType Directory -Path $extDir -Force | Out-Null
$sevenZip = "${env:ProgramFiles}\7-Zip\7z.exe"
if ((Test-Path $sevenZip) -and $zipPath.EndsWith('.7z')) {
  & $sevenZip x $zipPath "-o$extDir" -y | Out-Null
} else {
  Expand-Archive -Path $zipPath -DestinationPath $extDir -Force
}

# Pulihkan ke lokasi asli (dan ke semua kandidat direktori profil user)
$targetProfileDirs = @(Get-AllUserProfileDirs $u)
if ($primaryProfDir -and ($targetProfileDirs -notcontains $primaryProfDir)) {
  $targetProfileDirs = @($primaryProfDir) + $targetProfileDirs
}

if (Test-Path (Join-Path $extDir 'Persist')) {
  Copy-TreeUnlocked (Join-Path $extDir 'Persist') 'C:\Persist'
}

# Pulihkan wallpaper tersimpan dari sesi sebelumnya
$savedWpDir = 'C:\Persist\Config'
if (Test-Path $savedWpDir) {
  $pubWpDir = 'C:\Users\Public\Pictures\XyRDP'
  New-Item -ItemType Directory -Path $pubWpDir -Force -ErrorAction SilentlyContinue | Out-Null
  Get-ChildItem -Path $savedWpDir -Filter 'wallpaper.*' -File -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-FileUnlocked $_.FullName (Join-Path $pubWpDir $_.Name) | Out-Null
  }
}

foreach ($userDir in $targetProfileDirs) {
  if (Test-Path (Join-Path $extDir 'User')) {
    foreach ($sub in @('Desktop', 'Documents', 'Downloads', 'Pictures', 'Videos', 'Music')) {
      $s = Join-Path $extDir "User\$sub"
      if (Test-Path $s) {
        $d = Join-Path $userDir $sub
        New-Item -ItemType Directory -Path $d -Force -ErrorAction SilentlyContinue | Out-Null
        Copy-TreeUnlocked $s $d
      }
    }
  }
  if (Test-Path (Join-Path $extDir 'Minecraft')) {
    $mcDst = Join-Path $userDir 'AppData\Roaming\.minecraft'
    New-Item -ItemType Directory -Path $mcDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Minecraft') $mcDst
  }
  if (Test-Path (Join-Path $extDir 'TLauncher')) {
    $tlDst = Join-Path $userDir 'AppData\Roaming\.tlauncher'
    New-Item -ItemType Directory -Path $tlDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'TLauncher') $tlDst
  }
  if (Test-Path (Join-Path $extDir 'Browsers\Chrome')) {
    $chDst = Join-Path $userDir 'AppData\Local\Google\Chrome\User Data'
    New-Item -ItemType Directory -Path $chDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Browsers\Chrome') $chDst
    Log "  Sesi login Google Chrome berhasil dipulihkan ke $chDst."
  }
  if (Test-Path (Join-Path $extDir 'Browsers\Edge')) {
    $edDst = Join-Path $userDir 'AppData\Local\Microsoft\Edge\User Data'
    New-Item -ItemType Directory -Path $edDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Browsers\Edge') $edDst
    Log "  Sesi login Microsoft Edge berhasil dipulihkan ke $edDst."
  }
  if (Test-Path (Join-Path $extDir 'Browsers\Brave')) {
    $brDst = Join-Path $userDir 'AppData\Local\BraveSoftware\Brave-Browser\User Data'
    New-Item -ItemType Directory -Path $brDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Browsers\Brave') $brDst
    Log "  Sesi login Brave Browser berhasil dipulihkan ke $brDst."
  }
  if (Test-Path (Join-Path $extDir 'Browsers\Firefox')) {
    $ffDst = Join-Path $userDir 'AppData\Roaming\Mozilla\Firefox'
    New-Item -ItemType Directory -Path $ffDst -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Browsers\Firefox') $ffDst
    Log "  Sesi login Mozilla Firefox berhasil dipulihkan ke $ffDst."
  }
  if (Test-Path (Join-Path $extDir 'DPAPI')) {
    Copy-TreeUnlocked (Join-Path $extDir 'DPAPI') $userDir
    Log "  Kunci enkripsi DPAPI pengguna berhasil dipulihkan ke $userDir."
  }
  if (Test-Path (Join-Path $extDir 'Roblox')) {
    $rbxBase = Join-Path $userDir 'AppData\Local'
    New-Item -ItemType Directory -Path $rbxBase -Force -ErrorAction SilentlyContinue | Out-Null
    Copy-TreeUnlocked (Join-Path $extDir 'Roblox') $rbxBase
    Log "  Data login & konfigurasi Roblox berhasil dipulihkan ke $rbxBase."
  }

  # KRITIKAL: Berikan kepemilikan & FullControl penuh kepada user '$u' atas seluruh file hasil restore
  # agar Chrome, Explorer & Game dapat membaca/menulis Cookies, Downloads, dan Desktop tanpa Access Denied!
  try {
    & icacls $userDir /grant "${u}:(OI)(CI)F" /grant "Administrators:(OI)(CI)F" /grant "SYSTEM:(OI)(CI)F" /T /C /Q 2>&1 | Out-Null
  } catch {}
}

if (Test-Path (Join-Path $extDir 'PrismLauncher')) {
  $plDst = 'C:\Games\PrismLauncher'
  New-Item -ItemType Directory -Path $plDst -Force | Out-Null
  Copy-TreeUnlocked (Join-Path $extDir 'PrismLauncher') $plDst
}
if (Test-Path 'C:\Persist\samp-settings.reg') {
  try { & reg.exe import 'C:\Persist\samp-settings.reg' 2>&1 | Out-Null } catch {}
}

# Jika sesi sebelumnya menyimpan Gaming Boost, terapkan kembali secara otomatis
if ((Test-Path 'C:\Persist\System\gaming-boost.json') -and (Test-Path 'C:\XyRDP\tools\Apply-AntiVM-Boost.ps1')) {
  try { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\XyRDP\tools\Apply-AntiVM-Boost.ps1' | Out-Null } catch {}
}

$restoredCount = (Get-ChildItem -Path $extDir -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object).Count
Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
Log "State sesi sebelumnya berhasil dipulihkan! ($restoredCount file dikembalikan ke profil $primaryProfDir)"
Update-Status @{ persist = @{ restored = $true; files = $restoredCount; source = $asset.name; profile = $primaryProfDir } } | Out-Null
exit 0
