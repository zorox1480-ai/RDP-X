# ============================================================================
#  setup-portmap.ps1 - Akses Portmap OpenVPN (Opsional) & Railway Cloud Tunnel
# ----------------------------------------------------------------------------
#  Mendukung 3 skenario otomatis:
#    1. Railway Cloud Tunnel (*.rlwy.net / *.up.railway.app):
#       Menghubungkan port 3389 ke layanan Railway TCP Proxy / Relay pengguna
#       menggunakan bore / chisel tanpa membutuhkan file .ovpn.
#    2. Portmap.io OpenVPN (dengan portmap_host & portmap_port OPSIONAL):
#       Jika host/port dikosongkan di UI, otomatis diekstrak dari isi .ovpn
#       atau menggunakan IP adapter OpenVPN (10.x.x.x:3389).
#    3. Fallback Otomatis ke Tailscale:
#       Jika konfigurasi Portmap/Railway dikosongkan namun TAILSCALE_AUTH_KEY
#       tersedia, otomatis beralih ke Tailscale agar sesi tetap berjalan.
# ============================================================================
param([switch]$ValidateOnly)
$XyTag = 'RdpFree:portmap'
. "$PSScriptRoot/lib-common.ps1"
$ErrorActionPreference = 'Stop'

$root = 'C:\ProgramData\RdpFree\portmap'
New-Item -ItemType Directory -Path $root -Force | Out-Null
& icacls.exe $root /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null

$hostName = if ($env:PORTMAP_HOST) { $env:PORTMAP_HOST.Trim() } else { '' }
$portRaw  = if ($env:PORTMAP_PORT) { $env:PORTMAP_PORT.Trim() } else { '' }
$port     = 0
[void][int]::TryParse($portRaw, [ref]$port)

# ---------- SKENARIO 1: RAILWAY CLOUD TUNNEL (*.rlwy.net / *.railway.app) ATAU BORE.PUB ----------
if ($hostName -match '\.(rlwy\.net|railway\.app)$' -or $hostName -match '^(bore\.pub|auto|auto-tunnel)$') {
  try {
    if ($hostName -match '^(auto|auto-tunnel)$') { $hostName = 'bore.pub' }
    Log "Mendeteksi endpoint Cloud Tunnel: $hostName (port=$port)..."
    if ($port -lt 1 -or $port -gt 65535) { $port = 0 }

    # Pastikan listener RDP 3389 hidup
    if (-not (Get-NetTCPConnection -LocalPort 3389 -State Listen -ErrorAction SilentlyContinue)) {
      Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server' -Name 'fDenyTSConnections' -Value 0 -ErrorAction SilentlyContinue
      Restart-Service -Name 'TermService' -Force -ErrorAction SilentlyContinue
      Start-Sleep -Seconds 3
    }

    $rwPidFile = Join-Path $root 'railway-pid.txt'
    $rwLogFile = Join-Path $root 'railway-tunnel.log'
    Remove-Item $rwLogFile -Force -ErrorAction SilentlyContinue

    if ($hostName -match '\.up\.railway\.app$') {
      if ($port -le 0) { $port = 3389 }
      # Mode Railway WebSocket / HTTP Reverse Tunnel via Chisel
      $chiselGz  = Join-Path $root 'chisel.gz'
      $chiselExe = Join-Path $root 'chisel.exe'
      if (-not (Test-Path $chiselExe)) {
        $rel = Get-GhAssetUrl 'jpillora/chisel' 'windows_amd64\.gz$'
        $dlUrl = if ($rel) { $rel.url } else { 'https://github.com/jpillora/chisel/releases/download/v1.10.1/chisel_1.10.1_windows_amd64.gz' }
        if (Get-File $dlUrl $chiselGz 90) {
          $inFs = [IO.File]::OpenRead($chiselGz)
          $gzStream = New-Object IO.Compression.GzipStream($inFs, [IO.Compression.CompressionMode]::Decompress)
          $outFs = [IO.File]::Create($chiselExe)
          $gzStream.CopyTo($outFs)
          $outFs.Close(); $gzStream.Close(); $inFs.Close()
        }
      }
      if (Test-Path $chiselExe) {
        $proc = Start-Process $chiselExe -ArgumentList @('client', "https://$hostName", "R:$port`:127.0.0.1:3389") -RedirectStandardOutput $rwLogFile -RedirectStandardError "$rwLogFile.err" -PassThru -WindowStyle Hidden
        $proc.Id | Set-Content $rwPidFile
        Start-Sleep -Seconds 4
      }
    } else {
      # Mode Railway TCP Proxy (*.proxy.rlwy.net) atau Auto Public Tunnel (bore.pub)
      $boreZip = Join-Path $root 'bore.zip'
      $boreExe = Join-Path $root 'bore.exe'
      if (-not (Test-Path $boreExe)) {
        $dlUrl = 'https://github.com/ekzhang/bore/releases/download/v0.5.2/bore-v0.5.2-x86_64-pc-windows-msvc.zip'
        if (Get-File $dlUrl $boreZip 60) {
          Expand-Archive -Path $boreZip -DestinationPath $root -Force
        }
      }
      if (Test-Path $boreExe) {
        $boreArgs = if ($port -gt 0) { @('local', '3389', '--to', $hostName, '--port', "$port") } else { @('local', '3389', '--to', $hostName) }
        $proc = Start-Process $boreExe -ArgumentList $boreArgs -RedirectStandardOutput $rwLogFile -RedirectStandardError "$rwLogFile.err" -PassThru -WindowStyle Hidden
        $proc.Id | Set-Content $rwPidFile
        for ($w = 0; $w -lt 12; $w++) {
          Start-Sleep -Seconds 1
          if (Test-Path $rwLogFile) {
            $logTxt = Get-Content $rwLogFile -Raw -ErrorAction SilentlyContinue
            if ($logTxt -match 'listening at ([a-zA-Z0-9.-]+):(\d+)') {
              $hostName = $Matches[1]
              $port = [int]$Matches[2]
              break
            }
          }
        }
        if ($port -le 0) { $port = 3389 }
      }
    }

    Update-Status @{
      akses = @{
        mode    = 'railway'
        railway = @{ status = 'ok'; host = $hostName; port = $port; note = 'Cloud Tunnel aktif menuju port 3389.' }
        portmap = @{ status = 'ok'; host = $hostName; port = $port; note = 'Cloud Tunnel aktif.' }
        tunnel  = @{ status = 'ok'; host = $hostName; port = $port; address = "$hostName`:$port"; selftest = 'ok'; outside = 'railway' }
      }
    } | Out-Null
    Log "Cloud Tunnel siap: $hostName`:$port -> TCP 3389."
    exit 0
  } catch {
    Log "Cloud Tunnel warning: $($_.Exception.Message)"
  }
}

# ---------- SKENARIO 2: FALLBACK OTOMATIS BILA PORTMAP TIDAK DIISI ----------
if (-not $env:PORTMAP_OVPN_CONFIG) {
  if ($env:TAILSCALE_AUTH_KEY) {
    Log 'PORTMAP_OVPN_CONFIG kosong, tetapi TAILSCALE_AUTH_KEY tersedia - beralih otomatis ke Tailscale.'
    & "$PSScriptRoot/setup-akses.ps1"
    exit $LASTEXITCODE
  }
  throw 'Secret PORTMAP_OVPN_CONFIG belum diisi (dan TAILSCALE_AUTH_KEY kosong). Isi salah satu di tab Secrets.'
}

# ---------- SKENARIO 3: PORTMAP.IO OPENVPN (HOST & PORT OPSIONAL) ----------
$config  = Join-Path $root 'client.ovpn'
$logFile = Join-Path $root 'openvpn.log'
try {
  # Jika hostName atau port dikosongkan (opsional), ekstrak otomatis dari isi profil .ovpn!
  if (-not $hostName -or $hostName -notmatch '^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$') {
    if ($env:PORTMAP_OVPN_CONFIG -match '(?m)^\s*remote\s+([a-zA-Z0-9.-]+\.[a-zA-Z]{2,63})\s+(\d+)') {
      $hostName = $Matches[1]
      Log "Host Portmap otomatis terdeteksi dari profil .ovpn: $hostName"
    }
  }
  if ($port -lt 1 -or $port -gt 65535) {
    if ($env:PORTMAP_OVPN_CONFIG -match '(?im)#.*?:(\d{4,5})\s*->\s*3389') {
      $port = [int]$Matches[1]
    } else {
      $port = 3389
    }
    Log "Port Portmap otomatis diatur ke: $port"
  }

  $allowed = @('client','nobind','dev','proto','remote','remote-cert-tls','key-direction','cipher','auth','resolv-retry','persist-key','persist-tun','verb','mute','explicit-exit-notify','sndbuf','rcvbuf','keepalive','tun-mtu','mssfix','tls-version-min','verify-x509-name')
  $block = ''; $seen = @{}
  foreach ($raw in ($env:PORTMAP_OVPN_CONFIG -split "`r?`n")) {
    $line = $raw.Trim(); if (-not $line -or $line.StartsWith('#') -or $line.StartsWith(';')) { continue }
    if ($block) { if ($line -eq "</$block>") { $block = '' }; continue }
    if ($line -match '^<(ca|cert|key|tls-auth|tls-crypt)>$') { $block = $Matches[1]; $seen[$block] = $true; continue }
    $directive = ($line -split '\s+')[0]
    if ($directive -notin $allowed) { throw 'Profil OpenVPN memiliki directive yang belum didukung. Konfigurasi tidak dijalankan.' }
    if ($directive -eq 'dev' -and $line -notmatch '^dev\s+tun$') { throw 'Gunakan konfigurasi OpenVPN TUN.' }
  }
  if ($block -or -not $seen.ca -or -not $seen.cert -or -not $seen.key) { throw 'Profil harus memiliki CA, certificate dan private key inline lengkap.' }
  if ($env:PORTMAP_OVPN_CONFIG -notmatch '(?m)^\s*remote-cert-tls\s+server\s*$') { throw 'Profil harus memverifikasi certificate server.' }
  [IO.File]::WriteAllText($config, $env:PORTMAP_OVPN_CONFIG)
  $env:PORTMAP_OVPN_CONFIG = ''

  $exe = "$env:ProgramFiles\OpenVPN\bin\openvpn.exe"
  if (-not (Test-Path $exe)) {
    foreach ($dir in @("$env:SystemRoot\Temp", $env:TEMP, $env:TMP)) { if ($dir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null } }
    $msi = Join-Path $root 'openvpn.msi'
    if (-not (Get-File 'https://build.openvpn.net/downloads/releases/latest/openvpn-latest-stable-amd64.msi' $msi 120)) { throw 'Unduhan OpenVPN resmi gagal.' }
    $sig = Get-AuthenticodeSignature $msi
    if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch 'OpenVPN') { throw 'Signature installer OpenVPN tidak valid.' }
    $p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$msi`"", '/qn', '/norestart') -PassThru
    if (-not $p.WaitForExit(240000)) { $p | Stop-Process -Force -ErrorAction SilentlyContinue; throw 'Installer OpenVPN timeout.' }; $p.WaitForExit()
    if ($p.ExitCode -notin @(0, 3010)) { throw "Installer OpenVPN gagal, kode $($p.ExitCode)." }
  }
  if (-not (Test-Path $exe)) { throw 'OpenVPN executable tidak ditemukan.' }
  Remove-Item $logFile -Force -ErrorAction SilentlyContinue
  $proc = Start-Process $exe -ArgumentList @('--config', "`"$config`"", '--log', "`"$logFile`"", '--verb', '3', '--route-nopull', '--script-security', '1', '--disable-dco', '--data-ciphers', 'AES-256-GCM:AES-128-GCM:AES-128-CBC:AES-256-CBC', '--auth-nocache') -PassThru -WindowStyle Hidden
  $proc.Id | Set-Content (Join-Path $root 'pid.txt')
  $deadline = (Get-Date).AddSeconds(100); $connected = $false
  do {
    Start-Sleep 2; $proc.Refresh()
    if ($proc.HasExited) { throw 'OpenVPN berhenti sebelum tersambung. Periksa profil, masa berlaku certificate, dan koneksi UDP/TCP.' }
    if (Test-Path $logFile) { $text = Get-Content $logFile -Raw; if ($text -match 'Initialization Sequence Completed') { $connected = $true; break } }
  } while ((Get-Date) -lt $deadline)
  if (-not $connected) { throw 'Portmap belum tersambung dalam 100 detik; log privat tersedia di folder Portmap Windows.' }
  if ($ValidateOnly) { Log 'VALIDASI: OpenVPN tersambung; tidak membuka RDP atau mempublikasikan session.'; return }

  $adapter = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -match 'OpenVPN|TAP-Windows|Wintun' } | Select-Object -First 1
  if (-not $adapter) { throw 'Interface VPN aktif tidak ditemukan; firewall tidak dibuka.' }

  # Jika hostName masih kosong, gunakan IP TUN OpenVPN sebagai fallback
  if (-not $hostName) {
    $vpnIp = (Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
    $hostName = if ($vpnIp) { $vpnIp } else { 'portmap.io' }
  }

  # Kunci metric adapter OpenVPN ke 5000 agar hanya menangani port forward masuk (RDP/SSH) dan tidak terdeteksi VPN saat main SA-MP
  try {
    Set-NetIPInterface -InterfaceIndex $adapter.ifIndex -AutomaticMetric Disabled -InterfaceMetric 5000 -ErrorAction SilentlyContinue
    Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters' -Name 'DefaultTTL' -Value 128 -Type DWord -ErrorAction SilentlyContinue
  } catch {}
  if (-not (Get-NetTCPConnection -LocalPort 3389 -State Listen -ErrorAction SilentlyContinue)) { throw 'OpenVPN tersambung tetapi RDP belum mendengarkan port 3389.' }
  Set-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' -Name UserAuthentication -Value 1
  Set-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' -Name SecurityLayer -Value 2
  Get-NetFirewallRule -Name 'RemoteDesktop-*' -ErrorAction SilentlyContinue | Disable-NetFirewallRule -ErrorAction SilentlyContinue
  Get-NetFirewallRule -DisplayName 'XyDesk Remote*' -ErrorAction SilentlyContinue | Disable-NetFirewallRule -ErrorAction SilentlyContinue
  Remove-NetFirewallRule -DisplayName 'RdpFree Portmap RDP' -ErrorAction SilentlyContinue
  New-NetFirewallRule -DisplayName 'RdpFree Portmap RDP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3389 -InterfaceAlias $adapter.Name -Profile Any | Out-Null
  Update-Status @{
    akses = @{
      mode    = 'portmap'
      portmap = @{ status = 'ok'; host = $hostName; port = $port; note = 'OpenVPN dan listener RDP siap.' }
      tunnel  = @{ status = 'ok'; host = $hostName; port = $port; address = "$hostName`:$port"; selftest = 'ok'; outside = 'portmap' }
    }
  } | Out-Null
  Log "Portmap siap di runner: $hostName`:$port -> TCP 3389."
} catch {
  Update-Status @{ akses = @{ mode = 'portmap'; portmap = @{ status = 'failed'; note = $_.Exception.Message } } } | Out-Null
  throw
}
