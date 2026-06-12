param(
  [int]$BackendPort = 8090,
  [int]$FrontendPort = 5180,
  [switch]$StopExisting = $false
)

$ErrorActionPreference = 'Stop'

function Stop-ListeningProcessByPort {
  param([int]$Port)
  $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if (-not $connections) { Write-Host "No listening process on port $Port"; return }
  $processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique
  foreach ($processId in $processIds) {
    try { Stop-Process -Id $processId -Force -ErrorAction Stop; Write-Host "Stopped process $processId on port $Port" }
    catch { Write-Host "Failed to stop process $processId on port ${Port}: $($_.Exception.Message)" }
  }
}

function Test-PortFree {
  param([int]$Port)
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  return [bool](-not $conn)
}

function Get-FreePort {
  param([int]$StartPort, [int]$MaxTries = 50)
  for ($i = 0; $i -lt $MaxTries; $i++) {
    $candidate = $StartPort + $i
    if (Test-PortFree -Port $candidate) { return $candidate }
  }
  throw "No free port found in range $StartPort..$($StartPort + $MaxTries - 1)"
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $scriptRoot 'backend'
$frontendDir = Join-Path $scriptRoot 'frontend'

if (-not (Test-Path $backendDir)) { throw "Backend directory not found: $backendDir" }
if (-not (Test-Path $frontendDir)) { throw "Frontend directory not found: $frontendDir" }

if ($StopExisting) {
  Stop-ListeningProcessByPort -Port $BackendPort
  Stop-ListeningProcessByPort -Port $FrontendPort
}

# Auto-pick a free port if the preferred one is busy.
$resolvedBackendPort = Get-FreePort -StartPort $BackendPort
if ($resolvedBackendPort -ne $BackendPort) {
  Write-Host "Backend port $BackendPort is in use, falling back to $resolvedBackendPort"
  $BackendPort = $resolvedBackendPort
}
$resolvedFrontendPort = Get-FreePort -StartPort $FrontendPort
if ($resolvedFrontendPort -ne $FrontendPort) {
  Write-Host "Frontend port $FrontendPort is in use, falling back to $resolvedFrontendPort"
  $FrontendPort = $resolvedFrontendPort
}

# Persist resolved ports so stop-dev.ps1 can target the right ones.
$stateFile = Join-Path $scriptRoot '.pm-dev-state.json'
@{ backendPort = $BackendPort; frontendPort = $FrontendPort; startedAt = (Get-Date).ToString('o') } |
  ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8

Write-Host "Starting Project Management backend on port $BackendPort..."
$backendJob = Start-Job -Name 'pm-backend' -ScriptBlock {
  param([string]$Dir, [int]$Port)
  Set-Location $Dir
  $env:SERVER_PORT = "$Port"
  & mvn spring-boot:run 2>&1 | ForEach-Object { $_.ToString() }
} -ArgumentList $backendDir, $BackendPort

Write-Host "Waiting for backend to be ready on port $BackendPort..."
$maxWait = 120
$elapsed = 0
$backendReady = $false
while ($elapsed -lt $maxWait) {
  try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/api/projects" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
    if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500) { $backendReady = $true; break }
  } catch { }
  Start-Sleep -Seconds 2
  $elapsed += 2
}
if (-not $backendReady) {
  Write-Warning "Backend did not become ready within $maxWait seconds. Continuing anyway."
}

Write-Host "Starting frontend on port $FrontendPort..."
$frontendJob = Start-Job -Name 'pm-frontend' -ScriptBlock {
  param([string]$Dir, [int]$Port, [int]$ApiPort)
  Set-Location $Dir
  $env:BACKEND_PORT = "$ApiPort"
  $env:PM_FRONTEND_PORT = "$Port"
  if (-not (Test-Path 'node_modules')) { & npm install }
  & npx vite --port $Port --strictPort 2>&1 | ForEach-Object { $_.ToString() }
} -ArgumentList $frontendDir, $FrontendPort, $BackendPort

Write-Host ""
Write-Host "==============================================="
Write-Host " Project Management running"
Write-Host "   Backend  : http://127.0.0.1:$BackendPort"
Write-Host "   Frontend : http://127.0.0.1:$FrontendPort"
Write-Host "   H2 Console: http://127.0.0.1:$BackendPort/h2-console"
Write-Host " Press Ctrl+C to stop streaming logs (jobs keep running)."
Write-Host " Use stop-dev.ps1 to shut down."
Write-Host "==============================================="
Write-Host ""

try {
  while ($true) {
    Receive-Job -Job $backendJob | ForEach-Object { "[backend] $_" }
    Receive-Job -Job $frontendJob | ForEach-Object { "[frontend] $_" }
    Start-Sleep -Milliseconds 500
  }
} finally {
  # When the user Ctrl+C's we leave the jobs running; stop-dev.ps1 cleans them up.
}
