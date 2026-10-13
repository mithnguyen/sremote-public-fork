# S-remote state recovery
#   irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/fix-state.ps1 | iex
# Repairs machines where an old installer missed the original install dir
# (e.g. C:\sremote) and did a fresh install into %LOCALAPPDATA% — wiping the
# visible workspaces. This script updates the ORIGINAL dir in place (keeping
# its state.json and re-attaching its live session broker), then removes the
# duplicate install dir the bad update created.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Write-Host "==> S-remote state recovery"

# ---------- current install = what the running server reports ------------------
$cur = $null
try { $r = Invoke-RestMethod 'http://localhost:2209/api/info' -TimeoutSec 3
      if ($r.root) { $cur = $r.root } } catch {}
if (-not $cur) { $cur = Join-Path $env:LOCALAPPDATA 'S-remote' }
Write-Host "    current install: $cur"

# ---------- find the ORIGINAL install dir (has state.json / live broker) -------
$found = @{}
function Add-Found([string]$d) {
    if (-not $d) { return }
    try {
        # must look like an S-remote install, not just any dir with state.json
        if (-not (Test-Path (Join-Path $d 'state.json'))) { return }
        $ours = (Test-Path (Join-Path $d 'server.js')) -or
                (Test-Path (Join-Path $d 'agent\session-host.js')) -or
                (Test-Path (Join-Path $d 'public\app.js')) -or
                (Test-Path (Join-Path $d 'install.ps1'))
        if ($ours) { $found[[IO.Path]::GetFullPath($d).TrimEnd('\')] = $true }
    } catch {}
}
function Get-PathFromCmd([string]$raw) {
    if (-not $raw) { return $null }
    $raw = [Environment]::ExpandEnvironmentVariables($raw)
    if ($raw -match '"([A-Za-z]:[\\/][^"]+)"') { return $Matches[1] }
    if ($raw -match '([A-Za-z]:[\\/][^\s"'']+)') { return $Matches[1] }
    return $null
}
function Add-ExeParent($m) {
    if (-not $m) { return }
    $d = Split-Path $m -Parent
    Add-Found $d
    Add-Found (Split-Path (Split-Path $d -Parent) -Parent)   # <dir>\agent\tray -> <dir>
}

# 1) drive scan: top-level dirs, plus one level deeper for *remote*-named dirs
foreach ($drv in (Get-PSDrive -PSProvider FileSystem)) {
    Get-ChildItem $drv.Root -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
        Add-Found $_.FullName
        Get-ChildItem $_.FullName -Directory -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'remote' } | ForEach-Object { Add-Found $_.FullName }
    }
}
# 2) well-known spots - current user AND every profile under <sysdrive>\Users
$well = @("$env:LOCALAPPDATA\S-remote", "$env:USERPROFILE\S-remote", "$env:USERPROFILE\sremote",
          "$env:USERPROFILE\Desktop\S-remote", "$env:USERPROFILE\Documents\S-remote", "$env:ProgramData\S-remote")
Get-ChildItem "$env:SystemDrive\Users" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $u = $_.FullName
    $well += "$u\AppData\Local\S-remote", "$u\S-remote", "$u\sremote", "$u\Desktop\S-remote", "$u\Documents\S-remote"
}
foreach ($d in $well) { Add-Found $d }
# 3) autostart: scheduled task actions + Run keys (env vars expanded, args ok)
try { foreach ($a in (Get-ScheduledTask -TaskName 'S-remote' -ErrorAction Stop).Actions) {
          Add-ExeParent (Get-PathFromCmd $a.Execute)
          Add-ExeParent (Get-PathFromCmd $a.Arguments) } } catch {}
foreach ($rk in 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run',
                'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run') {
    try { Add-ExeParent (Get-PathFromCmd (Get-ItemProperty $rk -Name 'S-remote' -ErrorAction Stop).'S-remote') } catch {}
}
# 4) live processes: broker + server.js absolute paths reveal deep install dirs
foreach ($p in (Get-CimInstance Win32_Process -Filter "name='node.exe'" -ErrorAction SilentlyContinue)) {
    if ($p.CommandLine -match '([A-Za-z]:[\\/][^"'']*)[\\/]agent[\\/]session-host\.js') { Add-Found $Matches[1] }
    if ($p.CommandLine -match '([A-Za-z]:[\\/][^"'']*server\.js)') { Add-Found (Split-Path $Matches[1] -Parent) }
}

$target = $null; $best = $null; $bestN = -1
foreach ($d in $found.Keys) {
    if (([IO.Path]::GetFullPath($d)) -eq ([IO.Path]::GetFullPath($cur))) { continue }
    $n = 0
    try { $n = @((Get-Content (Join-Path $d 'state.json') -Raw | ConvertFrom-Json).workspaces).Count } catch {}
    # live session broker? session-host.js runs with an absolute path arg,
    # so its command line contains "<dir>\agent\session-host.js"
    $esc = [regex]::Escape((Join-Path $d 'agent\session-host.js'))
    $live = [bool](Get-CimInstance Win32_Process -Filter "name='node.exe'" -ErrorAction SilentlyContinue |
             Where-Object { $_.CommandLine -match $esc })
    Write-Host ("    found: {0,-50} workspaces: {1}  broker: {2}" -f $d, $n, $(if ($live) {'ALIVE'} else {'dead'}))
    # a live broker holds the user's live terminal sessions -> always prefer it
    if ($live) { $target = $d; break }
    if ($n -gt $bestN) { $best = $d; $bestN = $n }
}
if (-not $target) { $target = $best }
if (-not $target) {
    Write-Host "    no older install with saved workspaces found - nothing to restore"
    return
}

# ---------- update the ORIGINAL dir in place ------------------------------------
Write-Host "    -> updating original install at $target"
Write-Host "       (server re-attaches its session broker -> workspaces + live sessions return)"
$env:SREMOTE_DIR = $target
Invoke-Expression (Invoke-RestMethod 'https://raw.githubusercontent.com/thsangyk-oss/sremote/main/install.ps1')

# ---------- remove the duplicate dir the bad update created ---------------------
function Test-BroadDir($d) {
    try { $d = [IO.Path]::GetFullPath($d).TrimEnd('\') } catch { return $true }
    $broad = @($env:SystemDrive.TrimEnd('\'), "$env:SystemDrive\Users", $env:USERPROFILE.TrimEnd('\'),
               $env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:ProgramData, $env:SystemRoot,
               "$env:USERPROFILE\Desktop", "$env:USERPROFILE\Documents", "$env:USERPROFILE\Downloads")
    return ($broad -contains $d)
}
if ([IO.Path]::GetFullPath($cur) -ne [IO.Path]::GetFullPath($target) -and (Test-Path $cur)) {
    if (Test-BroadDir $cur) {
        Write-Host ""
        Write-Host "    SKIP removing $cur - dir too broad, refusing to touch it"
    } else {
    Write-Host ""
    Write-Host "==> removing duplicate install at $cur"
    # kill only processes that ARE that install (exe inside it or our scripts run
    # from it) plus their descendants - never anything that merely names the path
    $esc = [regex]::Escape($cur.TrimEnd('\'))
    $ourFiles = '(server\.js|agent[\\/][^"\\'']*\.(js|exe|ps1)|start\.(cmd|bat)|install\.(bat|ps1)|SremoteTray\.exe|ScreenPro\.exe)'
    $procs  = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue
    $byPpid = @{}
    foreach ($p in $procs) { $byPpid[$p.ParentProcessId] += @($p.ProcessId) }
    $kill = @{}; $queue = New-Object System.Collections.Queue
    foreach ($p in $procs) {
        $seed = ($p.ExecutablePath -and $p.ExecutablePath -match "^$esc[\\/]") -or
                ($p.CommandLine -and $p.CommandLine -match "$esc[\\/]$ourFiles")
        if ($seed -and -not $kill.ContainsKey($p.ProcessId)) { $kill[$p.ProcessId] = $true; $queue.Enqueue($p.ProcessId) }
    }
    while ($queue.Count -gt 0) {
        $ppid = $queue.Dequeue()
        foreach ($c in @($byPpid[$ppid])) {
            if ($c -and -not $kill.ContainsKey($c)) { $kill[$c] = $true; $queue.Enqueue($c) }
        }
    }
    foreach ($k in $kill.Keys) { Stop-Process -Id $k -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 1
    try { Remove-Item $cur -Recurse -Force -ErrorAction Stop
          Write-Host "    removed $cur" }
    catch { Write-Host "    WARN: could not fully remove $cur - delete it manually after a reboot" }
    }
}

Write-Host ""
Write-Host "OK - S-remote restored to original dir: $target"
Write-Host "    reload the web UI - workspaces and live sessions should be back"
