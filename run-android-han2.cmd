@echo off
setlocal

set "HAN2_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "HAN2_EMULATOR=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"
set "HAN2_APK=%~dp0android\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%HAN2_ADB%" (
  echo [HAN2] adb.exe was not found.
  pause
  exit /b 1
)

"%HAN2_ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [HAN2] Starting Android emulator...
  start "HAN2 Emulator" "%HAN2_EMULATOR%" -avd HAN2_Pixel_8_API_36
)

echo [HAN2] Waiting for Android...
:wait_for_device
"%HAN2_ADB%" wait-for-device >nul 2>&1
set "HAN2_BOOT="
for /f "delims=" %%B in ('"%HAN2_ADB%" shell getprop sys.boot_completed 2^>nul') do set "HAN2_BOOT=%%B"
if not "%HAN2_BOOT%"=="1" (
  timeout /t 2 /nobreak >nul
  goto wait_for_device
)

if exist "%HAN2_APK%" (
  echo [HAN2] Installing latest APK...
  "%HAN2_ADB%" install -r "%HAN2_APK%" >nul
) else (
  echo [HAN2] APK not found. Using installed app.
)

echo [HAN2] Restoring keyboard settings...
"%HAN2_ADB%" shell ime enable com.han2.app/.Han2KeyboardService >nul
"%HAN2_ADB%" shell ime set com.han2.app/.Han2KeyboardService >nul
"%HAN2_ADB%" shell settings put secure show_ime_with_hard_keyboard 1

echo [HAN2] Starting app...
"%HAN2_ADB%" shell am force-stop com.han2.app
"%HAN2_ADB%" shell am start -n com.han2.app/.MainActivity >nul

echo [HAN2] Ready. HAN2 is the active keyboard.
endlocal
