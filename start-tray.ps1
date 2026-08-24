param(
  [int]$Port = 8090,
  [switch]$Rebuild = $false
)

# Launches the resident tray app: one Spring Boot process that serves both the
# API and the pre-built frontend on a single port, with a system-tray icon.
# The dev flow (start-dev.cmd) is separate and unaffected.

$ErrorActionPreference = 'Stop'

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
$distDir = Join-Path $scriptRoot 'frontend\dist'

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

# Auto-pick a free port if the preferred one is busy (same behaviour as start-dev.ps1).
# This lets tray mode coexist with a running dev backend — the tray opens whatever
# port it ends up on, since DesktopTray reads server.port.
$resolvedPort = Get-FreePort -StartPort $Port
if ($resolvedPort -ne $Port) {
  Write-Host "Port $Port is in use, falling back to $resolvedPort"
  $Port = $resolvedPort
}

# Locate the fat jar; build it if missing or -Rebuild was requested.
function Get-Jar {
  Get-ChildItem (Join-Path $backendDir 'target') -Filter 'project-management-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } |
    Select-Object -First 1
}

$jar = Get-Jar
if ($Rebuild -or -not $jar -or -not (Test-Path $distDir)) {
  Write-Host "Building tray artifact (first run or -Rebuild)..."
  & (Join-Path $scriptRoot 'build-tray.ps1')
  $jar = Get-Jar
}
if (-not $jar) { throw "Jar not found; build failed." }

# Prefer javaw so no console window is shown (app-like). Fall back to java.
$javaw = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javaw.exe' } else { 'javaw' }
if ($env:JAVA_HOME -and -not (Test-Path $javaw)) { $javaw = 'javaw' }

# Quote paths — the project dir ("Project Management") contains a space, and
# Start-Process does not auto-quote array arguments on Windows PowerShell.
$jarArgs = "-jar `"$($jar.FullName)`" --server.port=$Port --pm.tray.enabled=true --pm.web.dist=`"$distDir`""

Write-Host "Starting Project Management (tray) on port $Port ..."
# cwd = backend so H2 (./data) and logs (./logs) match the dev flow.
Start-Process -FilePath $javaw -ArgumentList $jarArgs -WorkingDirectory $backendDir | Out-Null

Write-Host ""
Write-Host "==============================================="
Write-Host " Project Management (tray) launching"
Write-Host "   UI    : http://127.0.0.1:$Port"
Write-Host "   Tray  : look for the icon near the clock"
Write-Host " Right-click the tray icon -> 退出 to stop."
Write-Host "==============================================="
