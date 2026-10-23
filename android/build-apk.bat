@echo off
rem Rebuild the S-remote Android app.
rem Toolchain lives in %USERPROFILE%\android-dev (portable JDK 17 + SDK + Gradle).
set JAVA_HOME=%USERPROFILE%\android-dev\tools\jdk-17.0.20.1+1
set ANDROID_HOME=%USERPROFILE%\android-dev\sdk
"%USERPROFILE%\android-dev\tools\gradle-8.9\bin\gradle" assembleRelease assembleDebug --no-daemon
echo.
echo release: app\build\outputs\apk\release\app-release.apk
echo debug:   app\build\outputs\apk\debug\app-debug.apk
