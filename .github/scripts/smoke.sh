#!/usr/bin/env bash
# Installs the minified release APK and drives every main screen through the
# regular screenshot flow. Fails if the app process dies or logs a crash.
set -euo pipefail

PKG=com.bartixxx.parked

on_exit() {
  status=$?
  if [ "$status" -ne 0 ]; then
    adb logcat -d -v threadtime > logcat.txt || true
  fi
  exit "$status"
}
trap on_exit EXIT

adb install -r "$RUNNER_TEMP/Parked-smoke.apk"
adb logcat -c
python3 .github/scripts/screenshots.py
adb shell pidof "$PKG"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "::error::App crashed while exercising the screens"
  adb logcat -d -v threadtime | tee logcat.txt
  exit 1
fi

echo "Release APK exercised every main screen without crashing"
adb logcat -d -v threadtime | tee logcat.txt | tail -n 200
