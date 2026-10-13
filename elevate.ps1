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

# ---------- 1. task must exist and run with highest privileges ----------
$taskExists = $false
schtasks /query /tn $taskName >$null 2>&1
if ($LASTEXITCODE -eq 0) { $taskExists = $true }

if ($taskExists) {
    schtasks /change /tn $taskName /rl HIGHEST | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: could not raise task run level"; return }
    Write-Host "task '$taskName' now runs with highest privileges"
} else {
    # install used the HKCU Run fallback - locate install dir, then create task
    $dir = $null
    try { $dir = (Invoke-RestMethod 'http://localhost:2209/api/info' -TimeoutSec 4).root } catch {}
    if (-not $dir) {
        try {
            $v = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'S-remote' -ErrorAction Stop).'S-remote'
            $exe = if ($v -match '"([^"]+)"') { $Matches[1] } else { $v }
            $d1 = Split-Path $exe -Parent
            $d2 = Split-Path (Split-Path $d1 -Parent) -Parent  # <dir>\agent\tray -> <dir>
            if (Test-Path (Join-Path $d1 'server.js')) { $dir = $d1 }
            elseif (Test-Path (Join-Path $d2 'server.js')) { $dir = $d2 }
        } catch {}
    }
    if (-not $dir) { Write-Host "ERROR: cannot locate the S-remote install dir"; return }
    $trayExe = Join-Path $dir 'agent\tray\SremoteTray.exe'
    $launch = if (Test-Path $trayExe) { "`"$trayExe`"" } else { "`"$(Join-Path $dir 'start.cmd')`"" }
    schtasks /create /f /tn $taskName /tr $launch /sc onlogon /rl highest | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: could not create elevated task"; return }
    Write-Host "created elevated autostart task '$taskName'"
}

# ---------- 2. restart the whole chain under the elevated token ----------
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
