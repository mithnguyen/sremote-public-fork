# S-remote — notes for agents

Read `ARCHITECTURE.md` first — full map of server/UI/protocol/state so you don't have to re-derive it.

## Layout
- `server.js` (Node, port 2209) + `agent/` (session broker, screen agents, tray) + `public/` (web UI, static, served from disk).
- `android/` — native Android client (Java, no AndroidX). Plan for the 2.0 redesign: `android/PLAN-v2.md`.
- `install.ps1` / `fix-state.ps1` — Windows installer + recovery. Keep them saved as **UTF-8 with BOM** (PowerShell 5.1 `-File` misparses non-ASCII otherwise).

## Web UI
- After editing `public/app.js` or `public/style.css`, bump `?v=` in `public/index.html` (cache busting).
- Syntax check: `node --check public/app.js`. No restart needed — static files are read from disk.

## Android build (Windows, portable toolchain in `%USERPROFILE%\android-dev`)
```
cd android && build-apk.bat
```
(sets JAVA_HOME=`%USERPROFILE%\android-dev\tools\jdk-17.0.20.1+1`, ANDROID_HOME=`%USERPROFILE%\android-dev\sdk`, runs gradle 8.9 `assembleRelease assembleDebug`).
Installed on both dev machines (`C:\Users\User`, `C:\Users\ADMIN`); only `User` has the release keystore. `ADMIN` also has an emulator (AVD `sremote34`, API 34 x86_64) for smoke tests.
Outputs: `android/app/build/outputs/apk/{release/app-release.apk,debug/app-debug.apk}`.
Release signing via `android/keystore.properties` (never print/commit secrets). Verify:
`%USERPROFILE%\android-dev\sdk\build-tools\<ver>\apksigner.bat verify --print-certs app-release.apk` → `CN=S-remote`.

Detector unit test (no Android needed): compile `Detector.java` + a test `main` with the portable `javac` into a temp dir and run with `java`; delete the temp test afterwards.

## Git / release
- Commit identity: `git -c user.name="thsangyk-oss" -c user.email="thsangyk-oss@users.noreply.github.com" commit ...`
- GitHub token: from git credential manager (`printf 'protocol=https\nhost=github.com\n\n' | git credential fill`) — never echo it.
- Release body JSON must be written as UTF-8 by node (curl on Windows mangles non-ASCII), then `POST /repos/thsangyk-oss/sremote/releases`, upload assets to `uploads.github.com/.../assets?name=...`.
- Every release MUST include `sremote-server.zip` (installer downloads `releases/latest/download/sremote-server.zip`). Build it with
  `git archive HEAD server.js package.json package-lock.json install.ps1 install.bat start.bat start.cmd fix-state.ps1 README.md public agent -o temp-upload/sremote-server.zip`
  (git archive excludes locally compiled exes).
