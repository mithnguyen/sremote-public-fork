# S-remote

Direct remote terminal over **Tailscale** — no relay server, no token, no cloud.
Web UI (xterm.js) + PTY sessions that persist across restarts.

## Requirements

- **Node.js 18+**
- **Tailscale** installed and logged into your tailnet

## Install / update (Windows)

One command in PowerShell — installs or updates in place, keeping
`state.json` and `data/`:

```powershell
irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/install.ps1 | iex
```

The script scans for **every** existing install (running server, live
session broker, scheduled task, Run keys, drive scan, well-known dirs),
downloads `sremote-server.zip` from the latest
[release](https://github.com/thsangyk-oss/sremote/releases), installs
Node.js portable if missing, runs `npm install --omit=dev`, registers a
`S-remote` scheduled task for autostart, and starts the server.
Install dir defaults to `%LOCALAPPDATA%\S-remote` — override with
`$env:SREMOTE_DIR`, skip the overwrite prompt with `$env:SREMOTE_YES=1`,
preview detection only with `$env:SREMOTE_PROBE_ONLY=1`.

**Several installs found?** If the scan detects more than one S-remote
dir (e.g. a bad update dropped a copy into `%LOCALAPPDATA%`), it asks:

- `[1]` fresh install into the currently-running dir — others stay
  untouched, their workspaces are **not** migrated
- `[2]` fix + install into the original dir — keeps workspaces,
  re-attaches live sessions, merges newer state from the duplicate,
  then stops the duplicate *(default)*

Pre-answer non-interactively with `$env:SREMOTE_MODE = 'fresh'` or `'fix'`.

**The installer never deletes folders.** Duplicates are only stopped and
listed for you to remove by hand. A dir counts as an install only when a
running / registered S-remote points at it (server, session broker, tray,
autostart task or Run key), or — for dirs found by scanning the disk — when
it carries the `.sremote-install` marker every install writes (or is the
default `%LOCALAPPDATA%\S-remote`). Source checkouts (`.git`), forks and
copies of the project are never picked up by the scan.

**Workspaces gone after updating to v1.10.x or older?** Those installers
could miss a custom install dir (e.g. `C:\sremote`) and do a fresh
install under `%LOCALAPPDATA%` — your old install (workspaces + live
sessions) is still on disk. Run this to update the original dir in
place and stop the duplicate (its folder is left for you to delete):

```powershell
irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/fix-state.ps1 | iex
```

**Admin rights in every terminal session?** Sessions inherit the token of
the service chain (task → tray → server → PTY), so elevate the autostart
task once and every session gets admin — no UAC prompts:

```powershell
irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/elevate.ps1 | iex
```

The script self-elevates, switches the `S-remote` task to "run with highest
privileges" and restarts the chain — live terminal sessions end, workspaces
are kept. New installs run from an elevated shell do this automatically;
set `$env:SREMOTE_LIMITED=1` before `irm` to opt out.

Then open from any device in the tailnet:

```
http://<machine-tailscale-ip>:2209
```

**From source (any OS):** clone/copy this folder and run `node server.js`
(`npm install` first if `node_modules` is absent — `node-pty` needs a
prebuilt binary for your platform).

Access is restricted to tailnet IPs (`100.64.0.0/10`) and localhost — anything
else gets 403 before reaching the app.

### First run on Windows

`install.bat` adds the firewall rule automatically when elevated. To add it
manually, limited to the Tailscale interface:

```powershell
New-NetFirewallRule -DisplayName "S-remote" -Direction Inbound `
  -Program "C:\Program Files\nodejs\node.exe" -LocalPort 2209 -Action Allow `
  -InterfaceAlias "Tailscale"
```

## Autostart

**Windows:** the `irm` installer registers the `S-remote` task
automatically, pointing at the tray app when present (see below); it
falls back to the `HKCU\...\Run` key when `schtasks` needs admin.
Equivalent manual command:

```powershell
schtasks /create /tn "S-remote" /tr "C:\path\to\S-remote\agent\tray\SremoteTray.exe" `
  /sc onlogon /rl limited /f
```

### Tray app (no console window)

`agent/tray/SremoteTray.cs` compiles at install time to a `/winexe`
binary — **zero console windows**. It sits in the notification area
(drawn "S" icon: green = running, gray = stopped) and owns the
`node server.js` child process. Right-click menu:

- **Open Web UI** — browser at `localhost:2209`
- **Start / Stop server** — kills by child handle, or by port-2209
  ownership when the server was launched outside the tray
- **Start with Windows** — toggles the HKCU Run key
- **Exit** — asks whether to stop the server too

The scheduled task / `start.cmd` / HKCU Run entry all prefer the tray
exe when compiled; without it the old `start /min node` path is used.

**Linux (systemd):**

```ini
[Unit]
Description=S-remote
After=network.target tailscaled.service

[Service]
ExecStart=/usr/bin/node /opt/S-remote/server.js
Restart=always
User=you

[Install]
WantedBy=multi-user.target
```

**macOS (launchd):** create `~/Library/LaunchAgents/com.S-remote.plist`
with a `KeepAlive` ProgramArguments entry pointing at `server.js`.

## Platform notes

- **Windows:** shells are `powershell` / `cmd` / `pwsh`; the folder picker
  scans drives `A:`–`Z:`.
- **Linux/macOS:** shells are detected from `$SHELL` plus `/bin/bash`,
  `/usr/bin/zsh`, `/bin/zsh`, `/bin/sh` (and `pwsh` if on PATH); the folder
  picker lists `/` and `~` instead of drives.

## Sessions survive server restarts

PTYs are owned by `agent/session-host.js` — a detached broker daemon the
server spawns on demand and talks to over a per-install named pipe
(`\\.\pipe\sremote-sessions-*`) / unix socket (`data/host.sock`).
Restarting or updating `server.js` only drops the WebSocket layer; shells
and everything running inside them (dev servers, agents, builds) keep
running, and scrollback buffers stay resident. Broker crash → server
respawns it; sessions restore from `data/sessions.json` +
`data/scrollback/` (respawned shells — same as the old restart path).

## File explorer

The **File transfer** card on the home page opens a full file explorer
for the whole machine (not just workspaces): drive list → Home/Desktop/
Documents/Downloads/workspace shortcuts, breadcrumb + back/fwd history,
sortable Name/Size/Modified columns, filter box, Ctrl/Shift multi-select,
right-click context menu, drag-drop upload.

- Download: file → direct; folder or multi-select → auto-zipped first.
- Upload: up to 4 GB per file, progress bar, into the current folder.
- Archives: **Extract** (zip/tar/tgz/7z/rar via bsdtar, Expand-Archive
  fallback) and **Compress → zip** (PowerShell Compress-Archive).
- Copy/Cut/Paste (cross-drive moves handled), rename, delete, mkdir,
  new file, copy-path, **Open terminal here**.
- Deep link `#fx` or `#fx=C:\path` opens the explorer at that folder.
- Server API: `base=@abs` unlocks absolute paths in `/api/files`,
  `/api/file`, `/api/upload`, `/api/fileop` (new ops: `copy`, `move`,
  `extract`, `zip`). Anyone reaching the UI already has full shell
  access, so this exposes nothing new.

## Remote screen (Windows)

The 🖥 button (or **Remote screen** on the home page) opens the host
desktop in its own tab — `screen.html`; inside the Android app it opens in
place and Back returns. AnyDesk/RustDesk-style control:

- **Desktop**: mouse and keyboard go straight to the host (real key
  down/up, every button, wheel incl. horizontal). The local pointer takes
  the shape of the host cursor. **Fullscreen** uses Keyboard Lock so Win,
  Alt+Tab and Esc reach the host (hold Esc to leave).
- **Phone / tablet — Trackpad mode** (default): drag one finger to move the
  pointer, tap = click, double-tap-and-hold or hold = drag, two-finger
  tap = right click, two-finger drag = scroll, pinch = zoom (the view
  follows the pointer), three-finger tap = keyboard.
  **Touch mode**: tap exactly where you point, hold = right click.
- **Keyboard** on phones uses a hidden field, so IME / autocorrect /
  Vietnamese input work; the keys panel adds sticky Ctrl/Alt/Shift/Win
  (double-tap = lock), Esc/Tab/arrows/F-keys and shortcuts.
- **Clipboard** both ways, also over plain `http://` on the tailnet:
  Ctrl+V pastes this device's clipboard on the host (via the browser's
  paste event); text copied on the host offers a one-tap Copy, and the
  clipboard panel can set the host clipboard or type text.
- Toolbar: monitor picker (multi-monitor, or all screens), quality
  (Auto / High / Balanced / Low data), live stats (resolution, fps,
  bandwidth, latency), block host input, refresh, Task Manager, Win+L.

### Native agent

`agent/screen-pro/ScreenPro.cs` is compiled on the host with the `csc.exe`
that ships with .NET Framework (no download, ~30 KB exe). The server builds
it automatically the first time the screen is opened and again whenever the
source changes (`ScreenPro.ver` holds the source hash).

- Capture: **DXGI Desktop Duplication** of the chosen monitor (event
  driven, idle desktop ≈ zero traffic); GDI BitBlt for "all screens", when
  duplication is unavailable, or when a driver returns all-black frames.
  UAC / lock screen interrupts DXGI — the agent falls back and retries.
  DPI aware, so scaled (125 %/150 %) displays click exactly.
- **64 px tile diffing**, dirty tiles merged into row runs and JPEG
  encoded; tiles sent blurry (low quality) are re-sent sharp once the
  screen settles.
- **Flow control**: every frame costs a credit; the server grants credits
  as viewers ack frames (max 2 in flight), so a slow link never queues old
  frames — changes coalesce and latency stays low.
- The cursor is not baked into frames: position (60 Hz) and shape go out
  as JSON and the viewer draws it locally.
- Input via `SendInput` (absolute virtual-desktop mouse, vk + scan code
  keys, Unicode text). `BlockInput` needs the elevated task (`elevate.ps1`).
  `Ctrl+Alt+Del` and secure-desktop prompts are not reachable (Windows
  security boundary — same as RustDesk without its service).
- Fallback: when the agent can't be built, the page uses the PowerShell
  agent (`agent/screen-agent.ps1`, GDI+ shots + SendKeys) in basic mode.

WebSocket protocol: `{type:"screen", pro:true, op}` with `sub {flow,w,q,fps,mon}`
/ `unsub` / `ack` / `config` / `mon` / `refresh` and input ops `move`, `mrel`,
`down`/`up`/`click {b}`, `wheel {dy,dx}`, `kd`/`ku {vk}`, `combo`, `type`,
`clip`, `block`; frames arrive as binary `F` messages, `info` / `cur` /
`shape` / `clip` as JSON.

## HTTP API

Base path for file/git endpoints is `base=` — either `ws:<workspace-id>` or an
absolute directory. All paths are jailed under the base.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/info` | Host info: hostname, tailscale IP, available shells |
| GET | `/api/browse?path=` | Dir listing for the folder picker (drives when empty) |
| POST | `/api/mkdir` | `{parent, name}` — create directory |
| GET/POST | `/api/workspaces` | List / create workspace `{path, name}` |
| PATCH/DELETE | `/api/workspaces/:id` | Rename `{name}` / remove workspace |
| GET | `/api/files?base=&path=` | List directory under base |
| GET | `/api/file?base=&path=` | Read file (`&dl=1` attachment, `&head=1` first 512 KB) |
| POST | `/api/upload?base=&name=&dir=` | Raw-body upload into `<base>/<dir>` (default `temp-upload`) |
| GET/POST | `/api/sessions` | List / create PTY session `{shell, cwd, workspace, name}` |
| DELETE | `/api/sessions/:id` | Kill session |
| GET | `/api/peers` | Tailnet peers + probe of their S-remote instance |
| GET | `/api/devports` | Listening TCP ports + owning process name |
| GET | `/api/git?base=` | `{isRepo, branch, changed}` for a base dir |
| GET | `/api/gitdiff?base=` | `{stat, diff, truncated}` vs `HEAD` (diff capped ~256 KB) |
| WS | `/ws` | Terminal I/O: `attach` / `in` / `out` / `resize` / `create` / `rename` / `kill`; screen share: `type:"screen"` `sub`/`unsub`/`shot`/`click`/`down`/`up`/`move`/`scroll`/`type`/`key` |

Uploads that land in `<base>/temp-upload/` are added to `.git/info/exclude`
when the base is a git repo, and files there are swept after 7 days.

## Files

| Path | Purpose |
|---|---|
| `server.js` | HTTP + WS + PTY host (single file) |
| `public/` | Web UI — home, terminal tabs, file explorer, remote screen |
| `public/screen.html` | Remote screen page (own tab) — `screen.js` / `screen.css` |
| `agent/screen-pro/ScreenPro.cs` | Native screen agent (DXGI capture, SendInput), built on the host on first use |
| `agent/screen-agent.ps1` | Windows screen capture + input agent (spawned on demand) | — basic-mode fallback
| `agent/session-host.js` | Session broker daemon — owns PTYs + scrollback across server restarts |
| `state.json` | Persisted workspaces + session records (auto-created) |
| `data/scrollback/` | Per-session scrollback buffers (auto-created) |

## Architecture

```
client browser ──Tailscale WireGuard──> server.js :2209 ──pipe──> session-host ──ConPTY──> shell
                                        (frontend; restartable)      (broker; outlives server)
```

No signaling, no TURN, no tunnel. Pairing = being on the same tailnet.
