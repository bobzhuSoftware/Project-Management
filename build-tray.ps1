param(
  [switch]$SkipFrontend = $false
)

# Builds the "app-like" tray artifact:
#   1) builds the React frontend (frontend/dist)
#   2) packages the Spring Boot fat jar (backend/target/*.jar)
# Run this after changing frontend or backend code. The dev flow (start-dev.cmd)
# is unaffected — this only produces the artifacts that start-tray.cmd runs.

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $scriptRoot 'backend'
$frontendDir = Join-Path $scriptRoot 'frontend'

# ── Java override (optional) — same mechanism as start-dev.ps1 ────────────────
$javaHomeFile = Join-Path $scriptRoot '.java-home'
if (Test-Path $javaHomeFile) {
  $jh = (Get-Content $javaHomeFile -Raw).Trim()
  if ($jh) {
    $env:JAVA_HOME = $jh
    $env:Path = "$jh\bin;$env:Path"
    Write-Host "[java] JAVA_HOME -> $jh"
  }
}
# ── Node override (optional) ─────────────────────────────────────────────────
$nodeHomeFile = Join-Path $scriptRoot '.node-home'
if (Test-Path $nodeHomeFile) {
  $nh = (Get-Content $nodeHomeFile -Raw).Trim()
  if ($nh) {
    $env:NODE_HOME = $nh
    $env:Path = "$nh;$env:Path"
    Write-Host "[node] NODE_HOME -> $nh"
  }
}

if (-not $SkipFrontend) {
  Write-Host "Building frontend..."
  Push-Location $frontendDir
  try {
    if (-not (Test-Path 'node_modules')) { & npm install }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed" }
  } finally {
    Pop-Location
  }
} else {
  Write-Host "Skipping frontend build (-SkipFrontend)."
}

Write-Host "Packaging backend fat jar..."
Push-Location $backendDir
try {
  & mvn -q -DskipTests clean package
  if ($LASTEXITCODE -ne 0) { throw "Backend package failed" }
} finally {
  Pop-Location
}

$jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'project-management-*.jar' |
  Where-Object { $_.Name -notlike '*.original' } |
  Select-Object -First 1
if (-not $jar) { throw "Jar not found under backend/target" }

Write-Host ""
Write-Host "==============================================="
Write-Host " Tray artifact ready"
Write-Host "   Jar     : $($jar.FullName)"
Write-Host "   Frontend: $(Join-Path $frontendDir 'dist')"
Write-Host " Launch with start-tray.cmd"
Write-Host "==============================================="
