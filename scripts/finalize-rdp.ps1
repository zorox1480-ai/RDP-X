# RdpFree: disconnect only its Tailscale/Portmap/Railway session, never kill unrelated SSH processes.
$XyTag='RdpFree:finalize'
. "$PSScriptRoot/lib-common.ps1"
$exe="$env:ProgramFiles\Tailscale\tailscale.exe"
if (Test-Path $exe) {
  try { & $exe down 2>$null | Out-Null; & $exe logout 2>$null | Out-Null } catch { Log 'Tailscale perlu diperiksa pemilik tailnet' }
}
Log 'Sesi dihentikan. Mesin self-hosted tidak dihapus otomatis.'

$portmapRoot='C:\ProgramData\RdpFree\portmap'
foreach ($pf in @('pid.txt', 'railway-pid.txt')) {
  $pidFile = Join-Path $portmapRoot $pf
  if (Test-Path $pidFile) {
    $pidValue = 0
    if ([int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$pidValue)) {
      $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
      if ($process) { $process | Stop-Process -Force -ErrorAction SilentlyContinue }
    }
  }
}
Remove-Item (Join-Path $portmapRoot 'client.ovpn'), (Join-Path $portmapRoot 'pid.txt'), (Join-Path $portmapRoot 'railway-pid.txt') -Force -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName 'RdpFree Portmap RDP' -ErrorAction SilentlyContinue
Log 'Tunnel sesi ini ditutup; konfigurasi privat lokal dibersihkan.'
