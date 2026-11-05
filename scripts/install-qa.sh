#!/usr/bin/env bash
# Installs the QA build and compiles it ahead of time, the way the Play Store's install would leave it.
# A plain `adb install` / Run from Android Studio leaves the app in the "verify" state (everything interpreted or JIT-ed
# until the system's idle job compiles it, hours later): the first openings of the player stutter far more then.
# Usage: scripts/install-qa.sh [apk]   (default: app/build/outputs/apk/qa/app-qa.apk), device from ANDROID_SERIAL or the only one.
set -euo pipefail
APK="${1:-$(dirname "$0")/../app/build/outputs/apk/qa/app-qa.apk}"
adb install -r "$APK"
adb shell cmd package compile -m speed -f com.ikko.aura.qa
adb shell dumpsys package com.ikko.aura.qa | grep -m1 "arm64:"
