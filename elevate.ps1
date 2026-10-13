# elevate.ps1 - make every S-remote terminal session run with admin rights.
#
# Why this works: scheduled task -> SremoteTray.exe -> node server.js ->
# session-host.js (broker) -> PTY shells. Every child inherits its parent's
# token, so an elevated task gives every session an elevated token - no UAC.
#
# Run:  irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/elevate.ps1 | iex
# Opt out later: run this file's counter-commands or reinstall with $env:SREMOTE_LIMITED=1

$taskName = 'S-remote'

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "S-remote elevate: relaunching in an elevated window (accept the UAC prompt)..."
    Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-Command',"irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/elevate.ps1 | iex; Write-Host ''; pause"
    return
}

Write-Host "S-remote elevate (running as administrator)"
Write-Host ""

# ---------- 1. locate the install dir (needed if the task must be recreated) --
function Get-PathFromCmd([string]$raw) {
    if (-not $raw) { return $null }
    $raw = [Environment]::ExpandEnvironmentVariables($raw)
    if ($raw -match '"([A-Za-z]:[\\/][^"]+)"') { return $Matches[1] }
    if ($raw -match '([A-Za-z]:[\\/][^\s"'']+)') { return $Matches[1] }
    return $null
}
function Resolve-InstallDir([string]$exe) {
    if (-not $exe) { return $null }
    $d1 = Split-Path $exe -Parent
    if ($d1 -and (Test-Path (Join-Path $d1 'server.js'))) { return $d1 }
    $d2 = Split-Path (Split-Path $d1 -Parent) -Parent          # <dir>\agent\tray -> <dir>
    if ($d2 -and (Test-Path (Join-Path $d2 'server.js'))) { return $d2 }
    return $null
}
$dir = $null
try { $r = Invoke-RestMethod 'http://localhost:2209/api/info' -TimeoutSec 4
      if ($r.root -and (Test-Path (Join-Path $r.root 'server.js'))) { $dir = $r.root } } catch {}
if (-not $dir) {
    try { foreach ($a in (Get-ScheduledTask -TaskName $taskName -ErrorAction Stop).Actions) {
              $dir = Resolve-InstallDir (Get-PathFromCmd $a.Execute)
              if (-not $dir) { $dir = Resolve-InstallDir (Get-PathFromCmd $a.Arguments) }
              if ($dir) { break } } } catch {}
}
if (-not $dir) {
    try { $v = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'S-remote' -ErrorAction Stop).'S-remote'
          $dir = Resolve-InstallDir (Get-PathFromCmd $v) } catch {}
}
if (-not $dir) {
    foreach ($drv in (Get-PSDrive -PSProvider FileSystem)) {
        if ($dir) { break }
        Get-ChildItem $drv.Root -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
            if ($dir) { return }
            if (Test-Path (Join-Path $_.FullName 'server.js')) { $script:dir = $_.FullName; return }
            Get-ChildItem $_.FullName -Directory -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'remote' } | ForEach-Object {
                    if (Test-Path (Join-Path $_.FullName 'server.js')) { $script:dir = $_.FullName } }
        }
    }
}
if (-not $dir) { Write-Host "ERROR: cannot locate the S-remote install dir"; return }
Write-Host "    install dir: $dir"
$trayExe = Join-Path $dir 'agent\tray\SremoteTray.exe'
$launch  = if (Test-Path $trayExe) { "`"$trayExe`"" } else { "`"$(Join-Path $dir 'start.cmd')`"" }

# ---------- 2. task must exist and run with highest privileges ----------------
# "schtasks /change /rl" re-registers credentials and asks for a run-as
# password - impossible on passwordless Microsoft accounts (Windows Hello).
# Set-ScheduledTask keeps the same principal and only flips RunLevel, so no
# password is ever requested. Recreating the task without /ru also never asks.
$taskExists = $false
schtasks /query /tn $taskName >$null 2>&1
if ($LASTEXITCODE -eq 0) { $taskExists = $true }

$done = $false
if ($taskExists) {
    try {
        $t = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $lt = $t.Principal.LogonType; if (-not $lt) { $lt = 'Interactive' }
        $pr = New-ScheduledTaskPrincipal -UserId $t.Principal.UserId -LogonType $lt -RunLevel Highest
        Set-ScheduledTask -TaskName $taskName -Principal $pr -ErrorAction Stop | Out-Null
        $done = $true
    } catch { $done = $false }
}
if (-not $done) {
    schtasks /delete /f /tn $taskName >$null 2>&1
    schtasks /create /f /tn $taskName /tr $launch /sc onlogon /rl highest | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: could not create elevated task"; return }
}
Write-Host "task '$taskName' now runs with highest privileges"

# ---------- 3. restart the whole chain under the elevated token ----------
# the detached session broker keeps whatever token it was born with, so it
# must be restarted too - this ENDS live terminal sessions (state.json and
# workspaces are untouched).
$brokerAlive = @(Get-CimInstance Win32_Process -Filter "name='node.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'session-host\.js' }).Count
if ($brokerAlive -gt 0) {
    $ans = ''
    try { $ans = Read-Host "live terminal sessions are running - restarting ends them. Continue? [Y/n]" } catch {}
    if ($ans -match '^[nN]') {
        Write-Host "aborted - task is already elevated; rerun this script later to finish"
        return
    }
}
Write-Host ""
Write-Host "restarting tray + server + session broker (live terminal sessions will end)..."
Get-Process SremoteTray -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "name='node.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'server\.js|session-host\.js' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
schtasks /end /tn $taskName >$null 2>&1
schtasks /run /tn $taskName >$null 2>&1
Start-Sleep -Seconds 3

try {
    $info = Invoke-RestMethod 'http://localhost:2209/api/info' -TimeoutSec 5
    Write-Host ""
    Write-Host "done - server up on $($info.hostname). New sessions run elevated."
} catch {
    Write-Host ""
    Write-Host "done - server is starting; open http://localhost:2209 shortly."
}
Write-Host "verify inside a session:  whoami /groups | findstr /i 12288   (High level = admin)"
