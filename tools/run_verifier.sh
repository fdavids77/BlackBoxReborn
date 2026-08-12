#!/usr/bin/env bash
# BlackBox Reborn — ART Offset Verifier runner (Linux/macOS)
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[1/3] Building instrumented test APK..."
./gradlew :Bcore:assembleDebug :Bcore:assembleAndroidTest --no-daemon

echo ""
echo "[2/3] Installing APKs on connected device..."
adb install -r "app/build/outputs/apk/debug/BlackBox_4.0.0_arm64-v8a-debug.apk"
adb install -r "Bcore/build/outputs/apk/androidTest/debug/Bcore-debug-androidTest.apk"

echo ""
echo "[3/3] Running ART Offset Verifier..."
echo "      Watch for ArtOffsetVerifier tag in logcat"
echo ""

adb logcat -c

adb shell am instrument -w \
  -e class top.niunaijun.blackbox.test.ArtOffsetVerifierTest \
  top.niunaijun.blackbox.test/androidx.test.runner.AndroidJUnitRunner

echo ""
echo "--- Logcat output (ArtOffsetVerifier only) ---"
adb logcat -d -s ArtOffsetVerifier:V
