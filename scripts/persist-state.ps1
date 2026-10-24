# ============================================================================
#  persist-state.ps1 - Simpan State Sesi XyRDP (Auto-Save ke GitHub Release)
# ----------------------------------------------------------------------------
#  Menyimpan:
#    - C:\Persist (Folder permanen pengguna)
#    - C:\Games (Save data / instalasi game di bawah batas ukuran)
#    - Desktop, Documents, Downloads, Pictures, Videos, Music pengguna RDP
#      (membaca dari semua kandidat folder profil: C:\Users\$u, ProfileList, C:\Users\$u.*)
#    - Sesi Login Browser (Google Chrome, Microsoft Edge, Brave, Firefox)
#      beserta kunci DPAPI (AppData\Roaming\Microsoft\Protect & Credentials)
#      dan file SQLite WAL/SHM/LevelDB menggunakan FileShare.ReadWrite
#      sehingga login Google/YouTube/GitHub tetap tersimpan meski Chrome sedang dibuka!
#    - AppData\Roaming\.minecraft, TLauncher, PrismLauncher, GTA San Andreas User Files,
#      dan konfigurasi Registry HKCU\Software\SAMP + Gaming Boost + Wallpaper
#  Lalu mengenkripsi arsip dengan AES-256-CBC (PBKDF2 dari RDP_PASSWORD /
#  GITHUB_TOKEN) dan mengunggahnya ke Release tersembunyi `xyrdp-state-latest`
#  di repositori pengguna sendiri.
# ============================================================================
param(
  [switch]$Checkpoint
)

$XyTag = if ($Checkpoint) { 'XyRDP:checkpoint' } else { 'XyRDP:persist' }
. "$PSScriptRoot/lib-common.ps1"

$cfg = Get-Cfg
if ($env:PERSIST_DATA -and @('tidak','0','false','no','off') -contains $env:PERSIST_DATA.Trim().ToLower()) {
  Log 'Persistensi data dinonaktifkan oleh input pengguna - lewati.'
  exit 0
}

# Fallback baca environment dari C:\XyRDP\tools\session-env.json bila dipanggil dari background job / Dashboard
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
if (-not $ghToken -or -not $ghRepo) {
  Log 'GITHUB_TOKEN atau GITHUB_REPOSITORY tidak tersedia - lewati persist state.'
  exit 0
}

$u = if ($env:RDP_USER) { $env:RDP_USER.Trim() } else { $cfg.rdp_user }
$p = if ($env:RDP_PASSWORD) { $env:RDP_PASSWORD.Trim() } else { '' }
$secretKey = if ($p.Length -ge 4) { "XyRDP-Persist-$u-$p" } else { "XyRDP-Persist-$ghRepo" }

$workDir  = 'C:\XyRDP\state-work'
$stageDir = Join-Path $workDir 'stage'
$zipPath  = Join-Path $workDir 'xyrdp-state.7z'
$encPath  = Join-Path $workDir 'xyrdp-state.bin'

Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

Log "Menyiapkan penyimpanan state sesi untuk user '$u' (mode=$(if ($Checkpoint) { 'Checkpoint Live' } else { 'Final Shutdown' }))..."

# Saat akhir sesi (bukan checkpoint live), tutup browser secara halus agar SQLite me-flush WAL ke Cookies & Login Data
if (-not $Checkpoint) {
  foreach ($brProc in @('chrome', 'msedge', 'brave', 'firefox')) {
    try {
      Get-Process -Name $brProc -ErrorAction SilentlyContinue | ForEach-Object {
        $_.CloseMainWindow() | Out-Null
      }
    } catch {}
  }
  Start-Sleep -Milliseconds 1200
}

function Get-DirSizeMB([string]$Path) {
  if (-not (Test-Path $Path)) { return 0 }
  try {
    $sum = (Get-ChildItem -Path $Path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    if (-not $sum) { return 0 }
    return [math]::Round($sum / 1MB, 1)
  } catch { return 0 }
}

function Copy-SafeFolder([string]$Src, [string]$Dst, [int]$MaxMB = 900) {
  if (-not (Test-Path $Src)) { return }
  $sz = Get-DirSizeMB $Src
  if ($sz -le 0) { return }
  if ($sz -gt $MaxMB) {
    Log "  Lewati $Src ($sz MB > batas $MaxMB MB)"
    return
  }
  Log "  Menyalin $Src ($sz MB)..."
  New-Item -ItemType Directory -Path $Dst -Force -ErrorAction SilentlyContinue | Out-Null
  Copy-TreeUnlocked $Src $Dst
}

# 1. C:\Persist (Folder khusus anti-reset)
Copy-SafeFolder 'C:\Persist' (Join-Path $stageDir 'Persist') 1200

# Simpan juga Wallpaper aktif ke Persist\Config agar sesi berikutnya otomatis memakai wallpaper yang sama
try {
  $wpPersistenceDir = Join-Path $stageDir 'Persist\Config'
  New-Item -ItemType Directory -Path $wpPersistenceDir -Force -ErrorAction SilentlyContinue | Out-Null
  $pubWpDir = 'C:\Users\Public\Pictures\XyRDP'
  if (Test-Path $pubWpDir) {
    Get-ChildItem -Path $pubWpDir -Filter 'wallpaper.*' -File -ErrorAction SilentlyContinue | ForEach-Object {
      Copy-FileUnlocked $_.FullName (Join-Path $wpPersistenceDir $_.Name) | Out-Null
    }
  }
} catch {}

# 2. Kumpulkan SEMUA folder profil tempat user mungkin menyimpan file / login browser
#    (C:\Users\$u, ProfileImagePath dari ProfileList, C:\Users\$u.*)
$profileDirs = @(Get-AllUserProfileDirs $u)
Log "  Kandidat folder profil user '$u': $($profileDirs -join ', ')"

# Folder standar pengguna (Downloads, Desktop, Documents, Pictures, Videos, Music)
foreach ($profDir in $profileDirs) {
  foreach ($sub in @('Desktop', 'Documents', 'Downloads', 'Pictures', 'Videos', 'Music')) {
    $s = Join-Path $profDir $sub
    if (Test-Path $s) {
      Copy-SafeFolder $s (Join-Path $stageDir "User\$sub") 600
    }
  }
}

# 3. Save Game & Konfigurasi Launcher (Minecraft, TLauncher, PrismLauncher, Roblox, GTA SA-MP)
foreach ($profDir in $profileDirs) {
  $mcDir = Join-Path $profDir 'AppData\Roaming\.minecraft'
  if (Test-Path $mcDir) {
    foreach ($mcSub in @('saves', 'resourcepacks', 'shaderpacks', 'mods', 'config', 'options.txt', 'servers.dat', 'launcher_profiles.json', 'TlauncherProfiles.json')) {
      $p = Join-Path $mcDir $mcSub
      if (Test-Path $p) {
        $dst = Join-Path $stageDir "Minecraft\$mcSub"
        if ((Get-Item $p -Force).PSIsContainer) {
          Copy-SafeFolder $p $dst 450
        } else {
          Copy-FileUnlocked $p $dst | Out-Null
        }
      }
    }
  }
  $tlDir = Join-Path $profDir 'AppData\Roaming\.tlauncher'
  if (Test-Path $tlDir) {
    Copy-SafeFolder $tlDir (Join-Path $stageDir 'TLauncher') 250
  }
}

if (Test-Path 'C:\Games\PrismLauncher\accounts.json') {
  Copy-FileUnlocked 'C:\Games\PrismLauncher\accounts.json' (Join-Path $stageDir 'PrismLauncher\accounts.json') | Out-Null
}
if (Test-Path 'C:\Games\PrismLauncher\prismlauncher.cfg') {
  Copy-FileUnlocked 'C:\Games\PrismLauncher\prismlauncher.cfg' (Join-Path $stageDir 'PrismLauncher\prismlauncher.cfg') | Out-Null
}
if (Test-Path 'C:\Games\PrismLauncher\instances') {
  Copy-SafeFolder 'C:\Games\PrismLauncher\instances' (Join-Path $stageDir 'PrismLauncher\instances') 600
}

# 3b. Sesi Login Browser (Chrome, Edge, Brave, Firefox) + DPAPI Master Keys + SQLite WAL/SHM
#     Menggunakan Copy-FileUnlocked (FileShare.ReadWrite) sehingga tetap ter-backup 100%
#     walaupun browser sedang dibuka saat auto-checkpoint!
function Copy-ChromiumSession([string]$BrowserName, [string]$UserDataDir) {
  if (-not (Test-Path $UserDataDir)) { return }
  $dstRoot = Join-Path $stageDir "Browsers\$BrowserName"
  New-Item -ItemType Directory -Path $dstRoot -Force -ErrorAction SilentlyContinue | Out-Null

  $localState = Join-Path $UserDataDir 'Local State'
  if (Test-Path $localState) {
    Copy-FileUnlocked $localState (Join-Path $dstRoot 'Local State') | Out-Null
  }

  $profiles = @('Default', 'Profile 1', 'Profile 2', 'Profile 3', 'Profile 4', 'Profile 5')
  foreach ($prof in $profiles) {
    $profSrc = Join-Path $UserDataDir $prof
    if (-not (Test-Path $profSrc)) { continue }
    $profDst = Join-Path $dstRoot $prof
    New-Item -ItemType Directory -Path $profDst -Force -ErrorAction SilentlyContinue | Out-Null

    # Daftar lengkap file SQLite utama + file jurnal/WAL (-wal, -shm, -journal)
    $essentialFiles = @(
      'Preferences',
      'Secure Preferences',
      'Bookmarks',
      'Bookmarks.bak',
      'Login Data',
      'Login Data-journal',
      'Login Data-wal',
      'Login Data-shm',
      'Login Data For Account',
      'Login Data For Account-journal',
      'Login Data For Account-wal',
      'Login Data For Account-shm',
      'Web Data',
      'Web Data-journal',
      'Web Data-wal',
      'Web Data-shm',
      'History',
      'History-journal',
      'History-wal',
      'Favicons',
      'Shortcuts',
      'Visited Links',
      'TransportSecurity',
      'Network\Cookies',
      'Network\Cookies-journal',
      'Network\Cookies-wal',
      'Network\Cookies-shm',
      'Network\Reporting and NEL',
      'Network\TransportSecurity',
      'Network\Trust Tokens',
      'Cookies',
      'Cookies-journal',
      'Cookies-wal',
      'Cookies-shm'
    )
    foreach ($rel in $essentialFiles) {
      $fSrc = Join-Path $profSrc $rel
      if (Test-Path -LiteralPath $fSrc) {
        $fDst = Join-Path $profDst $rel
        Copy-FileUnlocked $fSrc $fDst | Out-Null
      }
    }
    foreach ($subDir in @('Local Storage\leveldb', 'Session Storage', 'Extension State', 'Sync Data\LevelDB', 'Local Extension Settings', 'IndexedDB')) {
      $dSrc = Join-Path $profSrc $subDir
      if ((Test-Path $dSrc) -and ((Get-DirSizeMB $dSrc) -le 120)) {
        $dDst = Join-Path $profDst $subDir
        Copy-TreeUnlocked $dSrc $dDst
      }
    }
  }
  Log "  Sesi login browser $BrowserName (Cookies + WAL + Login Data + LevelDB) berhasil disalin."
}

foreach ($profDir in $profileDirs) {
  Copy-ChromiumSession 'Chrome' (Join-Path $profDir 'AppData\Local\Google\Chrome\User Data')
  Copy-ChromiumSession 'Edge'   (Join-Path $profDir 'AppData\Local\Microsoft\Edge\User Data')
  Copy-ChromiumSession 'Brave'  (Join-Path $profDir 'AppData\Local\BraveSoftware\Brave-Browser\User Data')

  $ffProfiles = Join-Path $profDir 'AppData\Roaming\Mozilla\Firefox'
  if (Test-Path $ffProfiles) {
    Copy-SafeFolder $ffProfiles (Join-Path $stageDir 'Browsers\Firefox') 200
  }

  # Kunci DPAPI Pengguna agar Cookie & Password Chrome tetap bisa didekripsi di VM sesi berikutnya
  foreach ($dpapiRel in @(
    'AppData\Roaming\Microsoft\Protect',
    'AppData\Roaming\Microsoft\Credentials',
    'AppData\Local\Microsoft\Credentials',
    'AppData\Roaming\Microsoft\Crypto'
  )) {
    $dpSrc = Join-Path $profDir $dpapiRel
    if (Test-Path $dpSrc) {
      $dpDst = Join-Path $stageDir "DPAPI\$dpapiRel"
      Copy-TreeUnlocked $dpSrc $dpDst
    }
  }

  # Roblox LocalStorage & ClientSettings (agar login akun Roblox juga tersimpan!)
  foreach ($rbxRel in @('AppData\Local\Roblox\LocalStorage', 'AppData\Local\Bloxstrap')) {
    $rSrc = Join-Path $profDir $rbxRel
    if (Test-Path $rSrc) {
      Copy-SafeFolder $rSrc (Join-Path $stageDir "Roblox\$($rbxRel -replace '^AppData\\Local\\','')") 150
    }
  }
}

# Ekspor Registry HKCU\Software\SAMP (Nickname & Path gta_sa.exe)
try {
  $sampRegOut = Join-Path $stageDir 'Persist\samp-settings.reg'
  New-Item -ItemType Directory -Path (Split-Path $sampRegOut -Parent) -Force -ErrorAction SilentlyContinue | Out-Null
  & reg.exe export "HKCU\Software\SAMP" $sampRegOut /y 2>&1 | Out-Null
} catch {}

# 4. Hitung apakah ada file yang akan disimpan & cek perubahan (Anti-Spam API untuk Akun Baru)
$allStagedFiles = @(Get-ChildItem -Path $stageDir -Recurse -File -Force -ErrorAction SilentlyContinue)
$fileCount = $allStagedFiles.Count
if ($fileCount -eq 0) {
  Log 'Tidak ada file baru di C:\Persist atau profil user untuk disimpan.'
  Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
  exit 0
}

$totalBytes = ($allStagedFiles | Measure-Object -Property Length -Sum).Sum
$latestTicks = ($allStagedFiles | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc.Ticks
$stateSig = "$fileCount|$totalBytes|$latestTicks"
$sigFile = 'C:\XyRDP\tools\last-checkpoint-sig.txt'
if ($Checkpoint -and (Test-Path $sigFile)) {
  $prevSig = (Get-Content $sigFile -Raw -ErrorAction SilentlyContinue).Trim()
  if ($prevSig -eq $stateSig) {
    Log "Tidak ada perubahan data sejak checkpoint terakhir ($fileCount file) - lewati upload API."
    Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 0
  }
}

Log "Mengompresi $fileCount file state..."
$sevenZip = "${env:ProgramFiles}\7-Zip\7z.exe"
if (Test-Path $sevenZip) {
  & $sevenZip a -t7z -mx=3 $zipPath "$stageDir\*" | Out-Null
} else {
  $zipPath = Join-Path $workDir 'xyrdp-state.zip'
  Compress-Archive -Path "$stageDir\*" -DestinationPath $zipPath -CompressionLevel Fastest -Force
}

if (-not (Test-Path $zipPath)) {
  Log 'Gagal membuat arsip state.'
  exit 0
}

$zipMB = [math]::Round((Get-Item $zipPath).Length / 1MB, 2)
Log "Arsip state dibuat ($zipMB MB). Mengenkripsi AES-256..."
if ($zipMB -gt 5400) {
  Log "Arsip melebihi batas maksimum multi-part (5400 MB) - lewati upload."
  Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
  exit 0
}

try {
  $salt = New-Object byte[] 16
  [System.Security.Cryptography.RNGCryptoServiceProvider]::Create().GetBytes($salt)
  $kdf = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($secretKey, $salt, 20000)
  $aes = [System.Security.Cryptography.Aes]::Create()
  $aes.KeySize = 256
  $aes.Key = $kdf.GetBytes(32)
  $aes.IV  = $kdf.GetBytes(16)
  $aes.Mode = [System.Security.Cryptography.CipherMode]::CBC
  $aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7

  $fsOut = [System.IO.File]::OpenWrite($encPath)
  $header = [System.Text.Encoding]::ASCII.GetBytes('XYST01')
  $fsOut.Write($header, 0, $header.Length)
  $fsOut.Write($salt, 0, $salt.Length)

  $cs = New-Object System.Security.Cryptography.CryptoStream($fsOut, $aes.CreateEncryptor(), [System.Security.Cryptography.CryptoStreamMode]::Write)
  $fsIn = [System.IO.File]::OpenRead($zipPath)
  $fsIn.CopyTo($cs)
  $fsIn.Close()
  $cs.FlushFinalBlock()
  $cs.Close()
  $fsOut.Close()
} catch {
  Log "Enkripsi gagal: $($_.Exception.Message)"
  Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
  exit 0
}

$tag = 'xyrdp-state-latest'
$hdrs = @{
  'Authorization' = "Bearer $ghToken"
  'Accept'        = 'application/vnd.github+json'
  'User-Agent'    = 'XyRDP-State'
}

try {
  $rel = $null
  try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$ghRepo/releases/tags/$tag" -Headers $hdrs -TimeoutSec 30
  } catch {}

  if (-not $rel) {
    $body = @{
      tag_name   = $tag
      name       = 'XyRDP Persistent State (Auto-Save)'
      body       = 'Arsip terenkripsi AES-256 untuk menyimpan file dan game antar sesi RDP.'
      draft      = $false
      prerelease = $true
    } | ConvertTo-Json
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$ghRepo/releases" -Headers $hdrs -ContentType 'application/json' -Body $body -TimeoutSec 30
  }

  $baseAssetName = if ($zipPath.EndsWith('.7z')) { 'xyrdp-state.7z.enc' } else { 'xyrdp-state.zip.enc' }
  foreach ($a in @($rel.assets)) {
    if ($a.name -like 'xyrdp-state.*') {
      try {
        Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$ghRepo/releases/assets/$($a.id)" -Headers $hdrs -TimeoutSec 30 | Out-Null
      } catch {}
    }
  }

  $upHdrs = @{
    'Authorization' = "Bearer $ghToken"
    'Content-Type'  = 'application/octet-stream'
    'User-Agent'    = 'XyRDP-State'
  }
  $encLen = (Get-Item $encPath).Length
  $chunkMaxBytes = 1850L * 1024L * 1024L
  if ($encLen -le $chunkMaxBytes) {
    $uploadUrl = ($rel.upload_url -replace '\{\?name,label\}$', '') + "?name=$baseAssetName"
    Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $upHdrs -InFile $encPath -TimeoutSec 300 | Out-Null
    Log "State berhasil disimpan ke Release '$tag' ($baseAssetName, $zipMB MB, $fileCount file)."
  } else {
    # Multi-Part Split Archive Upload (>1.85 GB s/d 5.4 GB)
    $fsRead = [System.IO.File]::OpenRead($encPath)
    $partIdx = 1
    $buf = New-Object byte[] (16 * 1024 * 1024)
    try {
      while ($fsRead.Position -lt $fsRead.Length) {
        $partName = "{0}.part{1:D2}" -f $baseAssetName, $partIdx
        $partPath = Join-Path $workDir $partName
        $fsPart = [System.IO.File]::Create($partPath)
        $written = 0L
        while ($written -lt $chunkMaxBytes -and $fsRead.Position -lt $fsRead.Length) {
          $toRead = [int][Math]::Min([long]$buf.Length, ($chunkMaxBytes - $written))
          $n = $fsRead.Read($buf, 0, $toRead)
          if ($n -le 0) { break }
          $fsPart.Write($buf, 0, $n)
          $written += $n
        }
        $fsPart.Close()
        $uploadUrl = ($rel.upload_url -replace '\{\?name,label\}$', '') + "?name=$partName"
        Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $upHdrs -InFile $partPath -TimeoutSec 300 | Out-Null
        Remove-Item $partPath -Force -ErrorAction SilentlyContinue
        Log "  Uploaded multi-part chunk $partName ($([math]::Round($written / 1MB, 1)) MB)"
        $partIdx++
      }
    } finally {
      $fsRead.Close()
    }
  }

  # Simpan signature state terakhir agar checkpoint berikutnya tidak spam API bila belum ada perubahan
  New-Item -ItemType Directory -Path 'C:\XyRDP\tools' -Force -ErrorAction SilentlyContinue | Out-Null
  Set-Content -Path $sigFile -Value $stateSig -Encoding ASCII -Force

  # Catat waktu simpan terakhir untuk ditampilkan di XyDeskRemote Dashboard
  New-Item -ItemType Directory -Path 'C:\Persist\System' -Force -ErrorAction SilentlyContinue | Out-Null
  @{
    last_saved_utc = [DateTime]::UtcNow.ToString('o')
    size_mb        = $zipMB
    file_count     = $fileCount
    user           = $u
  } | ConvertTo-Json | Set-Content -Path 'C:\Persist\System\last-cloud-save.json' -Encoding UTF8 -Force
} catch {
  Log "Upload state gagal: $($_.Exception.Message)"
}

Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
exit 0
