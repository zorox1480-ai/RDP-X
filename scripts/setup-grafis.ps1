# ============================================================================
#  setup-grafis.ps1 - GPU Software Lengkap (Mesa3D OpenGL 4.6 + OpenGL ES 3.2
#                     + Vulkan 1.3 Lavapipe + System32/SysWOW64 + Java/Emulator)
# ----------------------------------------------------------------------------
#  1) Mengekstrak Mesa3D rilis MSVC secara terpisah untuk x64 (C:\XyRDP\grafis\mesa-x64)
#     dan x86 (C:\XyRDP\grafis\mesa-x86) tanpa saling menimpa arsitektur.
#  2) Memasang opengl32.dll, libgallium_wgl.dll, libglapi.dll, dxil.dll,
#     libEGL.dll, libGLESv1_CM.dll, libGLESv2.dll secara SYSTEM-WIDE ke
#     C:\Windows\System32 (64-bit) DAN C:\Windows\SysWOW64 (32-bit) (backup
#     asli disimpan di C:\XyRDP\grafis\orig), sehingga Minecraft (semua launcher)
#     dan Emulator (LDPlayer, BlueStacks, MuMu, Nox, MEmu, PPSSPP, dll.)
#     langsung mengenali OpenGL 4.6 & OpenGL ES 3.2 tanpa error driver!
#  3) Memasang Vulkan Runtime Loader (vulkan-1.dll) + Lavapipe ICD (vulkan_lvp.dll)
#     dan mendaftarkannya ke HKLM:\SOFTWARE\Khronos\Vulkan\Drivers.
#  4) Mengatur GALLIUM_DRIVER=llvmpipe, LIBGL_ALWAYS_SOFTWARE=1,
#     MESA_GL_VERSION_OVERRIDE=4.6, MESA_GLSL_VERSION_OVERRIDE=460,
#     MESA_GLES_VERSION_OVERRIDE=3.2 serta menyuntikkan OpenGL ke seluruh
#     runtime Java (termasuk Temurin JDK di C:\hostedtoolcache).
# ============================================================================

$XyTag = 'XyRDP:grafis'
. "$PSScriptRoot/lib-common.ps1"

$mode = if ($env:GRAFIS) { $env:GRAFIS.Trim().ToLower() } else { 'software' }
if (@('tidak','0','false','no','off','skip','none') -contains $mode) {
  Log 'setup grafis dilewati (input grafis=tidak)'
  Update-Status @{ grafis = @{ mode = 'native' } } | Out-Null
  exit 0
}

$work = 'C:\XyRDP\grafis'
$mesaDirX64 = Join-Path $work 'mesa-x64'
$mesaDirX86 = Join-Path $work 'mesa-x86'
$bkpSys32   = Join-Path $work 'orig\system32'
$bkpWow64   = Join-Path $work 'orig\syswow64'
foreach ($d in @($work, $mesaDirX64, $mesaDirX86, $bkpSys32, $bkpWow64)) {
  New-Item -ItemType Directory -Path $d -Force | Out-Null
}

$gAdapters = ''
try {
  $ad = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | ForEach-Object { "$($_.Name)" })
  $gAdapters = ($ad -join ' | ')
} catch {}

function Install-SysFile([string]$src, [string]$dst, [string]$bkpDir) {
  if (-not $src -or -not (Test-Path $src)) { return $false }
  try {
    if (Test-Path $dst) {
      $leaf = Split-Path $dst -Leaf
      $bkpFile = Join-Path $bkpDir $leaf
      if (-not (Test-Path $bkpFile)) {
        Copy-Item $dst $bkpFile -Force -ErrorAction SilentlyContinue
      }
      $company = ''
      try { $company = (Get-Item $dst -ErrorAction SilentlyContinue).VersionInfo.CompanyName } catch {}
      if ($company -notlike 'Mesa*') {
        & takeown.exe /f $dst /a 2>&1 | Out-Null
        & icacls.exe $dst /grant '*S-1-5-32-544:F' 2>&1 | Out-Null
      }
    }
    Copy-Item $src $dst -Force -ErrorAction Stop
    return $true
  } catch {
    Log "  warning Install-SysFile ($dst): $($_.Exception.Message)"
    return $false
  }
}

try {
  $asset = Get-GhAssetUrl 'pal1000/mesa-dist-win' 'mesa3d-.*-release-msvc\.7z$'
  if (-not $asset) {
    $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/pal1000/mesa-dist-win/releases/latest' `
      -Headers @{ 'User-Agent' = 'XyRDP' } -TimeoutSec 25
    $rawAsset = $rel.assets | Where-Object { $_.name -match 'release-msvc' -and $_.name -match '\.(7z|zip)$' } | Select-Object -First 1
    if ($rawAsset) {
      $asset = @{ name = $rawAsset.name; url = $rawAsset.browser_download_url; tag = $rel.tag_name }
    }
  }

  if (-not $asset) { throw 'Aset rilis Mesa3D tidak ditemukan dari GitHub API.' }

  $arc = Join-Path $work $asset.name
  Log "Mengunduh Mesa3D ($($asset.name))..."
  if (-not (Get-File $asset.url $arc 300)) {
    & curl.exe -fL --retry 3 --connect-timeout 20 -sS $asset.url -o $arc
  }

  $sevenZip = $null
  foreach ($p in @("$env:ProgramFiles\7-Zip\7z.exe", "${env:ProgramFiles(x86)}\7-Zip\7z.exe")) {
    if (Test-Path $p) { $sevenZip = $p; break }
  }
  if (-not $sevenZip) { $c = Get-Command 7z.exe -ErrorAction SilentlyContinue; if ($c) { $sevenZip = $c.Source } }

  $ext = Join-Path $work 'mesa-extract'
  Remove-Item $ext -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Path $ext -Force | Out-Null

  if ($sevenZip -and (Test-Path $arc)) {
    & $sevenZip x $arc "-o$ext" -y 2>&1 | Out-Null
    # Jika di dalam arsip terdapat sub-arsip (.7z/.zip), ekstrak ke sub-folder masing-masing agar x86 tidak menimpa x64!
    $foundGl = @(Get-ChildItem -Path $ext -Recurse -File -Filter 'opengl32.dll' -ErrorAction SilentlyContinue)
    if ($foundGl.Count -eq 0) {
      Get-ChildItem -Path $ext -Recurse -File -Include '*.7z','*.zip' -ErrorAction SilentlyContinue | ForEach-Object {
        $subOut = Join-Path $_.DirectoryName $_.BaseName
        New-Item -ItemType Directory -Path $subOut -Force | Out-Null
        & $sevenZip x $_.FullName "-o$subOut" -y 2>&1 | Out-Null
      }
    }
  }

  # Pisahkan direktori x64 dan x86 secara ketat
  $x64Dirs = @(Get-ChildItem -Path $ext -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^(x64|win64)$' -and (Test-Path (Join-Path $_.FullName 'opengl32.dll')) })
  $x86Dirs = @(Get-ChildItem -Path $ext -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^(x86|win32)$' -and (Test-Path (Join-Path $_.FullName 'opengl32.dll')) })

  if ($x64Dirs.Count -gt 0) {
    foreach ($d in $x64Dirs) {
      Copy-Item -Path "$($d.FullName)\*" -Destination $mesaDirX64 -Force -ErrorAction SilentlyContinue
    }
  } else {
    Get-ChildItem -Path $ext -Recurse -File -Include '*.dll','*.json' -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -notmatch '\\(x86|win32)\\' } |
      ForEach-Object { Copy-Item $_.FullName -Destination (Join-Path $mesaDirX64 $_.Name) -Force -ErrorAction SilentlyContinue }
  }

  if ($x86Dirs.Count -gt 0) {
    foreach ($d in $x86Dirs) {
      Copy-Item -Path "$($d.FullName)\*" -Destination $mesaDirX86 -Force -ErrorAction SilentlyContinue
    }
  } else {
    Get-ChildItem -Path $ext -Recurse -File -Include '*.dll','*.json' -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -match '\\(x86|win32)\\' } |
      ForEach-Object { Copy-Item $_.FullName -Destination (Join-Path $mesaDirX86 $_.Name) -Force -ErrorAction SilentlyContinue }
  }

  Remove-Item $ext -Recurse -Force -ErrorAction SilentlyContinue

  # 1. Pasang DLL OpenGL 4.6 & OpenGL ES 3.2 (EGL/GLESv2) ke System32 (64-bit) & SysWOW64 (32-bit)
  #    Ini kunci utama agar Minecraft (semua launcher) & Emulator Android (LDPlayer/BlueStacks/Nox/MuMu/MEmu/PPSSPP)
  #    langsung mendeteksi OpenGL 4.6 & OpenGL ES 3.2 secara system-wide!
  $sys32 = "$env:SystemRoot\System32"
  $wow64 = "$env:SystemRoot\SysWOW64"
  $coreGlDlls = @(
    'opengl32.dll',
    'libgallium_wgl.dll',
    'libglapi.dll',
    'dxil.dll',
    'libEGL.dll',
    'libGLESv1_CM.dll',
    'libGLESv2.dll',
    'zink_dri.dll'
  )

  $installedSys32 = 0
  foreach ($dllName in $coreGlDlls) {
    $src64 = Join-Path $mesaDirX64 $dllName
    if (Test-Path $src64) {
      if (Install-SysFile $src64 (Join-Path $sys32 $dllName) $bkpSys32) { $installedSys32++ }
    }
    $src86 = Join-Path $mesaDirX86 $dllName
    if ((Test-Path $wow64) -and (Test-Path $src86)) {
      Install-SysFile $src86 (Join-Path $wow64 $dllName) $bkpWow64 | Out-Null
    }
  }
  Log "Mesa3D OpenGL/GLES system-wide terpasang ke System32 ($installedSys32 DLL) & SysWOW64."

  # 2. Pasang Vulkan 1.3 Lavapipe (vulkan_lvp.dll + ICD) & Vulkan Runtime Loader (vulkan-1.dll)
  #    Penting untuk Emulator Android modern (BlueStacks/LDPlayer/MuMu/PPSSPP) & mod Minecraft Vulkan.
  $vkDir = Join-Path $work 'vulkan'
  New-Item -ItemType Directory -Path $vkDir -Force | Out-Null
  $lvpDll64 = Join-Path $mesaDirX64 'vulkan_lvp.dll'
  $lvpIcd64 = Get-ChildItem -Path $mesaDirX64 -File -Filter '*lvp_icd*.json' -ErrorAction SilentlyContinue | Select-Object -First 1
  $icdPath64 = $null
  if ((Test-Path $lvpDll64) -and $lvpIcd64) {
    Copy-Item $lvpDll64 $vkDir -Force -ErrorAction SilentlyContinue
    Copy-Item $lvpIcd64.FullName $vkDir -Force -ErrorAction SilentlyContinue
    $icdPath64 = Join-Path $vkDir $lvpIcd64.Name
    # Pastikan library_path di dalam JSON mengarah ke vulkan_lvp.dll yang valid
    try {
      $icdJson = Get-Content $icdPath64 -Raw | ConvertFrom-Json
      if ($icdJson.ICD) {
        $icdJson.ICD.library_path = (Join-Path $vkDir 'vulkan_lvp.dll')
        $icdJson | ConvertTo-Json -Depth 5 | Set-Content -Path $icdPath64 -Encoding UTF8 -Force
      }
    } catch {}
    [Environment]::SetEnvironmentVariable('VK_DRIVER_FILES', $icdPath64, 'Machine')
    [Environment]::SetEnvironmentVariable('VK_ADD_DRIVER_FILES', $icdPath64, 'Machine')
    [Environment]::SetEnvironmentVariable('VK_ICD_FILENAMES', $icdPath64, 'Machine')
    $env:VK_DRIVER_FILES = $icdPath64
    $env:VK_ADD_DRIVER_FILES = $icdPath64
    $env:VK_ICD_FILENAMES = $icdPath64
    try {
      $vkReg = 'HKLM:\SOFTWARE\Khronos\Vulkan\Drivers'
      New-Item -Path $vkReg -Force -ErrorAction SilentlyContinue | Out-Null
      New-ItemProperty -Path $vkReg -Name $icdPath64 -PropertyType DWord -Value 0 -Force -ErrorAction SilentlyContinue | Out-Null
    } catch {}
    Log "Vulkan Lavapipe ICD terdaftar: $icdPath64"
  }

  # Pasang vulkan-1.dll loader jika belum ada di System32
  $vkLoaderSys32 = Join-Path $sys32 'vulkan-1.dll'
  if (-not (Test-Path $vkLoaderSys32)) {
    $vkrtZip = Join-Path $work 'vulkan-runtime.zip'
    if (Get-File 'https://sdk.lunarg.com/sdk/download/latest/windows/vulkan-runtime-components.zip' $vkrtZip 180) {
      $zvk = Join-Path $work 'vkrt'
      Remove-Item $zvk -Recurse -Force -ErrorAction SilentlyContinue
      try {
        if ($sevenZip) { & $sevenZip x $vkrtZip "-o$zvk" -y 2>&1 | Out-Null }
        else { Expand-Archive -Path $vkrtZip -DestinationPath $zvk -Force }
        $v64 = Get-ChildItem $zvk -Recurse -File -Filter 'vulkan-1.dll' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\(x64|win64)\\' } | Select-Object -First 1
        if (-not $v64) { $v64 = Get-ChildItem $zvk -Recurse -File -Filter 'vulkan-1.dll' -ErrorAction SilentlyContinue | Select-Object -First 1 }
        if ($v64) {
          Copy-Item $v64.FullName $vkLoaderSys32 -Force -ErrorAction SilentlyContinue
          Log "Vulkan Loader (vulkan-1.dll) terpasang ke System32."
        }
        $v86 = Get-ChildItem $zvk -Recurse -File -Filter 'vulkan-1.dll' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\(x86|win32)\\' } | Select-Object -First 1
        if ($v86 -and (Test-Path $wow64)) {
          Copy-Item $v86.FullName (Join-Path $wow64 'vulkan-1.dll') -Force -ErrorAction SilentlyContinue
        }
      } catch {}
      Remove-Item $zvk -Recurse -Force -ErrorAction SilentlyContinue
    }
  }

  # 3. Set environment variable OpenGL 4.6 + OpenGL ES 3.2 di atas llvmpipe (100% stabil di RDP untuk Minecraft & Emulator)
  $envKey = 'Registry::HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Session Manager\Environment'
  Set-Reg $envKey 'GALLIUM_DRIVER'             'llvmpipe' 'String' | Out-Null
  Set-Reg $envKey 'LIBGL_ALWAYS_SOFTWARE'      '1'        'String' | Out-Null
  Set-Reg $envKey 'MESA_GL_VERSION_OVERRIDE'   '4.6'      'String' | Out-Null
  Set-Reg $envKey 'MESA_GLSL_VERSION_OVERRIDE' '460'      'String' | Out-Null
  Set-Reg $envKey 'MESA_GLES_VERSION_OVERRIDE' '3.2'      'String' | Out-Null
  [Environment]::SetEnvironmentVariable('GALLIUM_DRIVER', 'llvmpipe', 'Machine')
  [Environment]::SetEnvironmentVariable('LIBGL_ALWAYS_SOFTWARE', '1', 'Machine')
  [Environment]::SetEnvironmentVariable('MESA_GL_VERSION_OVERRIDE', '4.6', 'Machine')
  [Environment]::SetEnvironmentVariable('MESA_GLSL_VERSION_OVERRIDE', '460', 'Machine')
  [Environment]::SetEnvironmentVariable('MESA_GLES_VERSION_OVERRIDE', '3.2', 'Machine')
  $env:GALLIUM_DRIVER = 'llvmpipe'
  $env:LIBGL_ALWAYS_SOFTWARE = '1'
  $env:MESA_GL_VERSION_OVERRIDE = '4.6'
  $env:MESA_GLSL_VERSION_OVERRIDE = '460'
  $env:MESA_GLES_VERSION_OVERRIDE = '3.2'

  # Aktifkan kebijakan RDP WDDM / GPU Enumeration agar aplikasi DirectX/OpenGL/Emulator tidak menolak jalan di RDP
  $tsPol = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\Terminal Services'
  Set-Reg $tsPol 'bEnumerateHWBeforeSW' 1 'DWord' | Out-Null
  Set-Reg $tsPol 'fEnableWddmDriver'    1 'DWord' | Out-Null

  # 4. Pastikan Java bawaan runner (Temurin JDK 21 / 17 / 8 di C:\hostedtoolcache) terdaftar di JAVA_HOME & PATH
  #    sehingga walaupun user lupa mencentang minecraft_java=ya, Minecraft / TLauncher tetap langsung bisa jalan!
  try {
    $tcJavaRoot = 'C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk'
    if (Test-Path $tcJavaRoot) {
      $bestJava = Get-ChildItem -Path $tcJavaRoot -Recurse -File -Filter 'javaw.exe' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '21\.|17\.' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
      if (-not $bestJava) {
        $bestJava = Get-ChildItem -Path $tcJavaRoot -Recurse -File -Filter 'javaw.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
      }
      if ($bestJava) {
        $javaBin = $bestJava.DirectoryName
        $javaHome = Split-Path $javaBin -Parent
        $curPath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
        if ($curPath -notlike "*$javaBin*") {
          [Environment]::SetEnvironmentVariable('Path', "$javaBin;$curPath", 'Machine')
          $env:Path = "$javaBin;$env:Path"
        }
        if (-not [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')) {
          [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'Machine')
          $env:JAVA_HOME = $javaHome
        }
        Log "Java Runtime aktif untuk Minecraft: $javaHome"
      }
    }
  } catch {}

  # 5. Buat script helper Deploy-OpenGL-To-Java.ps1 untuk menyuntikkan OpenGL 4.6 ke semua folder Java, Minecraft, & Emulator
  $deployPs1 = Join-Path $work 'Deploy-OpenGL-To-Java.ps1'
  $deployCode = @'
$ErrorActionPreference = 'SilentlyContinue'
$mesaDirX64 = 'C:\XyRDP\grafis\mesa-x64'
$mesaDirX86 = 'C:\XyRDP\grafis\mesa-x86'
$srcGl64 = Join-Path $mesaDirX64 'opengl32.dll'
if (-not (Test-Path $srcGl64)) { exit 0 }
$srcSize64 = (Get-Item $srcGl64).Length

$targets64 = New-Object System.Collections.Generic.List[string]
foreach ($root in @(
  'C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk',
  'C:\Program Files\Eclipse Adoptium',
  'C:\Program Files\Java',
  'C:\Program Files\Microsoft',
  'C:\Program Files (x86)\Minecraft Launcher\runtime',
  'C:\Games\Minecraft',
  'C:\Games\PrismLauncher',
  'C:\Program Files\BlueStacks_nxt',
  'C:\Program Files\LDPlayer',
  'C:\LDPlayer',
  'C:\Nox\bin',
  'C:\Program Files\Microvirt\MEmu',
  'C:\Program Files\Netease\MuMuPlayerGlobal-12.0\shell',
  'C:\Persist'
)) {
  if (Test-Path $root) {
    Get-ChildItem -Path $root -Recurse -File -Include 'javaw.exe','java.exe','PrismLauncher.exe','Minecraft.Windows.exe','HD-Player.exe','dnplayer.exe','Nox.exe','MEmu.exe','MuMuPlayer.exe','PPSSPPWindows64.exe' -ErrorAction SilentlyContinue | ForEach-Object {
      $dir = $_.DirectoryName
      if (-not $targets64.Contains($dir)) { $targets64.Add($dir) }
    }
  }
}

Get-ChildItem -Path 'C:\Users' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
  foreach ($sub in @(
    'AppData\Roaming\.minecraft\runtime',
    'AppData\Roaming\PrismLauncher\java',
    'AppData\Roaming\.tlauncher\jvms',
    'AppData\Roaming\.tlauncher\legacy\Minecraft\jre',
    'AppData\Roaming\.feather\jre',
    'AppData\Local\Programs\lunarclient',
    'AppData\Local\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime'
  )) {
    $p = Join-Path $_.FullName $sub
    if (Test-Path $p) {
      Get-ChildItem -Path $p -Recurse -File -Include 'javaw.exe','java.exe' -ErrorAction SilentlyContinue | ForEach-Object {
        $dir = $_.DirectoryName
        if (-not $targets64.Contains($dir)) { $targets64.Add($dir) }
      }
    }
  }
}

foreach ($dir in $targets64) {
  $dstGl = Join-Path $dir 'opengl32.dll'
  # Hanya salin jika belum ada atau ukurannya berbeda, agar tidak mengganggu proses yang sedang berjalan
  if (-not (Test-Path $dstGl) -or ((Get-Item $dstGl -ErrorAction SilentlyContinue).Length -ne $srcSize64)) {
    Copy-Item -Path "$mesaDirX64\*.dll" -Destination $dir -Force -ErrorAction SilentlyContinue
  }
}
'@
  Set-Content -Path $deployPs1 -Value $deployCode -Encoding UTF8 -Force
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $deployPs1 2>&1 | Out-Null

  Log "Mesa3D OpenGL 4.6 + OpenGL ES 3.2 (llvmpipe) + Vulkan 1.3 (lavapipe) siap di System32, SysWOW64, dan $mesaDirX64"
  Update-Status @{ grafis = @{
    mode = 'software';
    mesa = $asset.tag;
    opengl = '4.6 (Mesa llvmpipe system-wide x64/x86 + OpenGL ES 3.2)';
    vulkan = 'lavapipe 1.3 (CPU)';
    adapters = $gAdapters;
    path = $mesaDirX64
  } } | Out-Null
  exit 0
} catch {
  Log "setup grafis gagal (non-fatal): $($_.Exception.Message)"
}
Update-Status @{ grafis = @{ mode = 'native-fallback'; adapters = $gAdapters } } | Out-Null
exit 0
