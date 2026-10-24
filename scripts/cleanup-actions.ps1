# ============================================================================
#  cleanup-actions.ps1 - Pembersihan Bertahap Run Lama (Aman untuk Akun Baru)
# ----------------------------------------------------------------------------
#  Menyimpan KEEP_RUNS run terbaru (default 5) dan menghapus maksimal 3 run
#  selesai paling lama per sesi dengan jeda sopan (1.2 detik) agar tidak
#  memicu rate-limit atau deteksi anti-abuse pada akun GitHub baru.
# ============================================================================
$ErrorActionPreference = 'Continue'
function Log([string]$m) { Write-Host "[XyRDP:cleanup] $m" }

$repo = $env:GITHUB_REPOSITORY
$wf   = $env:GH_WORKFLOW; if (-not $wf) { $wf = 'rdp-6h.yml' }
$keep = [int]($env:KEEP_RUNS -replace '\D',''); if ($keep -lt 1) { $keep = 5 }
$tok  = if ($env:CLEANUP_TOKEN) { $env:CLEANUP_TOKEN } else { $env:GITHUB_TOKEN }

if (-not $repo -or -not $tok) { Log 'repo/token tidak diketahui - skip'; exit 0 }

$hdrs = @{
  Authorization          = "Bearer $tok"
  Accept                 = 'application/vnd.github+json'
  'User-Agent'           = 'XyRDP-Workspace'
  'X-GitHub-Api-Version' = '2022-11-28'
}

try {
  $runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/workflows/$wf/runs?per_page=25" -Method Get -Headers $hdrs -TimeoutSec 25
  $list = @($runs.workflow_runs)
  if ($list.Count -le $keep) {
    Log "Total run ($($list.Count)) masih dalam batas aman ($keep) - tidak ada yang dihapus."
    exit 0
  }
  $old = @($list | Select-Object -Skip $keep | Where-Object { $_.status -notin @('in_progress', 'queued') } | Select-Object -Last 3)
  $n = 0
  foreach ($r in $old) {
    try {
      Start-Sleep -Milliseconds 1200
      Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$repo/actions/runs/$($r.id)" -Headers $hdrs -TimeoutSec 25 | Out-Null
      $n++
    } catch {}
  }
  Log "Pembersihan bertahap selesai: $n run lama dibersihkan."
} catch {
  Log "Cleanup dilewati: $($_.Exception.Message)"
}
exit 0
