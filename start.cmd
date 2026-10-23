@echo off
cd /d %~dp0
if exist "%~dp0node\node.exe" set "PATH=%~dp0node;%PATH%"
if exist "%~dp0agent\tray\SremoteTray.exe" (start "" "%~dp0agent\tray\SremoteTray.exe" & exit /b)
start "" /min node server.js
