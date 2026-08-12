@echo off
REM BlackBox Reborn — ART Offset Verifier runner (Windows)
REM Builds the instrumented test APK, installs it, runs the verifier,
REM and prints the COMPAT.md paste line to the console.
REM
REM Usage: tools\run_verifier.bat

setlocal

cd /d "%~dp0.."

echo [1/3] Building instrumented test APK...
call gradlew.bat :Bcore:assembleDebug :Bcore:assembleAndroidTest --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED. Check output above.
    exit /b 1
)

echo.
echo [2/3] Installing APKs on connected device...
adb install -r "Bcore\build\outputs\apk\debug\Bcore-debug.aar" 2>nul
adb install -r "app\build\outputs\apk\debug\BlackBox_4.0.0_arm64-v8a-debug.apk"
adb install -r "Bcore\build\outputs\apk\androidTest\debug\Bcore-debug-androidTest.apk"

echo.
echo [3/3] Running ART Offset Verifier...
echo       (results tagged ArtOffsetVerifier in logcat)
echo.

adb logcat -c

adb shell am instrument -w ^
  -e class top.niunaijun.blackbox.test.ArtOffsetVerifierTest ^
  top.niunaijun.blackbox.test/androidx.test.runner.AndroidJUnitRunner

echo.
echo --- Logcat output (ArtOffsetVerifier only) ---
adb logcat -d -s ArtOffsetVerifier:*

endlocal
