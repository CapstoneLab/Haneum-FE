@echo off
setlocal

set "HAN2_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "HAN2_APK=%~dp0android\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%HAN2_ADB%" (
  echo [HAN2] Android SDK adb.exe was not found.
  exit /b 1
)

pushd "%~dp0android"
call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1
popd

"%HAN2_ADB%" wait-for-device
"%HAN2_ADB%" install -r "%HAN2_APK%"
"%HAN2_ADB%" shell ime enable com.han2.app/.NativeKeyboardService
"%HAN2_ADB%" shell ime set com.han2.app/.NativeKeyboardService
"%HAN2_ADB%" shell am start -n com.han2.app/.NativeMainActivity

endlocal
