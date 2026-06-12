param(
  [int[]]$Ports = @(),
  [int]$BackendPort = 0
)

$ErrorActionPreference = 'Continue'

# Load resolved ports from start-dev state file if present.
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$stateFile = Join-Path $scriptRoot '.pm-dev-state.json'
if (Test-Path $stateFile) {
  try {
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
    if ($BackendPort -le 0 -and $state.backendPort) { $BackendPort = [int]$state.backendPort }
    if ($Ports.Count -eq 0 -and $state.backendPort -and $state.frontendPort) {
      $Ports = @([int]$state.frontendPort, [int]$state.backendPort)
    }
  } catch {
    Write-Host "Failed to read $stateFile : $($_.Exception.Message)"
  }
}
if ($BackendPort -le 0) { $BackendPort = 8090 }
if ($Ports.Count -eq 0) { $Ports = @(5180, 8090) }

# 1) Try graceful shutdown first so child projects get killed via @PreDestroy.
try {
  Write-Host "Requesting graceful shutdown on port $BackendPort..."
  Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/_internal/shutdown" -Method Post -TimeoutSec 5 | Out-Null
  # Give it a moment to kill child trees and unbind ports.
  Start-Sleep -Seconds 6
} catch {
  Write-Host "Graceful shutdown not reachable: $($_.Exception.Message)"
}

# 2) Force-kill anything still bound on the listed ports (PM itself or stragglers).
foreach ($port in $Ports) {
  $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if (-not $connections) { Write-Host "No listening process on port $port"; continue }
  $processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique
  foreach ($processId in $processIds) {
    try { Stop-Process -Id $processId -Force -ErrorAction Stop; Write-Host "Stopped process $processId on port $port" }
    catch { Write-Host "Failed to stop process $processId on port ${port}: $($_.Exception.Message)" }
  }
}

Get-Job -Name 'pm-backend','pm-frontend' -ErrorAction SilentlyContinue | ForEach-Object {
  Stop-Job $_ -ErrorAction SilentlyContinue
  Remove-Job $_ -Force -ErrorAction SilentlyContinue
}

if (Test-Path $stateFile) {
  Remove-Item $stateFile -ErrorAction SilentlyContinue
}
