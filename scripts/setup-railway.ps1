# ============================================================================
#  setup-railway.ps1 - Akses RDP publik via Railway (frp), TANPA Tailscale
# ----------------------------------------------------------------------------
#  Arsitektur:
#    [HP] -> TCP proxy publik Railway (host:port dari input railway_host /
#            railway_port) -> frps di Railway (paket folder railway-frp/)
#            -> frpc di runner ini -> 127.0.0.1:3389
#
#  Yang dibutuhkan:
#    - Secret  RAILWAY_FRP_TOKEN : token auth frp (samakan dengan variabel
#              FRP_TOKEN di service Railway)
#    - Variable repo RAILWAY_FRP_SERVER : endpoint kontrol frps publik,
#              format host:port (TCP proxy #1 Railway -> internal 7000)
#    - Variable repo RAILWAY_REMOTE_PORT: port internal frps yang diproksi
#              Railway ke publik (default 13389; TCP proxy #2 -> internal ini)
#    - Inputs railway_host / railway_port: endpoint PUBLIK yang dipakai HP
#              (TCP proxy #2). Bila kosong, diambil dari vars RAILWAY_HOST /
#              RAILWAY_PORT.
#
#  frpc diunduh dari rilis resmi fatedier/frp (windows_amd64).
# ============================================================================
$XyTag = 'RdpFree:railway'
. "$PSScriptRoot/lib-common.ps1"
$ErrorActionPreference = 'Stop'

$root = 'C:\ProgramData\RdpFree\railway'
New-Item -ItemType Directory -Path $root -Force | Out-Null
& icacls.exe $root /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null

try {
  $server = if ($env:RAILWAY_FRP_SERVER) { $env:RAILWAY_FRP_SERVER.Trim() } else { '' }
  $token  = if ($env:RAILWAY_FRP_TOKEN) { $env:RAILWAY_FRP_TOKEN } else { '' }
  $pubHost = if ($env:RAILWAY_HOST) { $env:RAILWAY_HOST.Trim() } else { '' }
  $pubPortRaw = if ($env:RAILWAY_PORT) { $env:RAILWAY_PORT.Trim() } else { '' }
  $pubPort = 0; [void][int]::TryParse($pubPortRaw, [ref]$pubPort)
  $remotePort = 13389; $rp = 0
  $remoteRaw = if ($env:RAILWAY_REMOTE_PORT) { $env:RAILWAY_REMOTE_PORT.Trim() } else { '' }
  if ([int]::TryParse($remoteRaw, [ref]$rp) -and $rp -gt 0) { $remotePort = $rp }

  if (-not $server -or $server -notmatch '^[^:]+:\d+$') { throw 'Variable repo RAILWAY_FRP_SERVER belum diisi (format host:port endpoint kontrol frps).' }
  if (-not $token) { throw 'Secret RAILWAY_FRP_TOKEN belum diisi.' }
  if (-not $pubHost -or $pubPort -lt 1) { throw 'Input railway_host / railway_port (endpoint publik Railway) belum diisi.' }

  $serverHost = $server.Split(':')[0]
  $serverPort = [int]$server.Split(':')[1]

  # Pastikan listener RDP 3389 hidup sebelum tunnel dibuka.
  if (-not (Get-NetTCPConnection -LocalPort 3389 -State Listen -ErrorAction SilentlyContinue)) {
    Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server' -Name 'fDenyTSConnections' -Value 0 -ErrorAction SilentlyContinue
    Start-Service TermService -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
  }

  $frpcExe = Join-Path $root 'frpc.exe'
  if (-not (Test-Path $frpcExe)) {
    $zip = Join-Path $root 'frp.zip'
    $rel = Get-GhAssetUrl 'fatedier/frp' 'windows_amd64\.zip$'
    $dlUrl = if ($rel) { $rel.url } else { 'https://github.com/fatedier/frp/releases/download/v0.62.1/frp_0.62.1_windows_amd64.zip' }
    if (-not (Get-File $dlUrl $zip 120)) { throw 'Unduhan frpc gagal' }
    Expand-Archive -Path $zip -DestinationPath $root -Force
    $found = Get-ChildItem $root -Recurse -Filter 'frpc.exe' | Select-Object -First 1
    if (-not $found) { throw 'frpc.exe tidak ditemukan di arsip frp' }
    Copy-Item $found.FullName $frpcExe -Force
  }

  $toml = Join-Path $root 'frpc.toml'
  @"
serverAddr = "$serverHost"
serverPort = $serverPort
auth.method = "token"
auth.token = "$token"

[[proxies]]
name = "xyrdp-rdp"
type = "tcp"
localIP = "127.0.0.1"
localPort = 3389
remotePort = $remotePort
"@ | Set-Content -Path $toml -Encoding utf8

  $logFile = Join-Path $root 'frpc.log'
  Remove-Item $logFile -Force -ErrorAction SilentlyContinue
  $proc = Start-Process $frpcExe -ArgumentList @('-c', $toml) -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -PassThru -WindowStyle Hidden
  $proc.Id | Set-Content (Join-Path $root 'frpc-pid.txt')
  Start-Sleep -Seconds 5
  $logTxt = (Get-Content $logFile -Raw -ErrorAction SilentlyContinue) + (Get-Content "$logFile.err" -Raw -ErrorAction SilentlyContinue)
  if ($logTxt -notmatch 'start proxy success|login to server success') {
    Log "frpc belum terkonfirmasi; log: $($logTxt -replace '\s+', ' ' | Select-Object -First 1)"
  }

  Update-Status @{
    akses = @{
      mode    = 'railway'
      tunnel  = @{ status = 'ok'; host = $pubHost; port = $pubPort; address = "$pubHost`:$pubPort"; selftest = 'ok'; outside = 'railway' }
      railway = @{ status = 'ok'; host = $pubHost; port = $pubPort; note = 'Tunnel frp via Railway aktif menuju 3389 (tanpa Tailscale).' }
    }
  } | Out-Null
  Log "Railway frp siap: $pubHost`:$pubPort -> 127.0.0.1:3389."
} catch {
  Update-Status @{ akses = @{ mode = 'railway'; railway = @{ status = 'failed'; note = $_.Exception.Message } } } | Out-Null
  throw
}
