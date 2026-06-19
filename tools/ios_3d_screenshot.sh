#!/usr/bin/env bash
# Autonomous visual loop for the iOS 3D board (three.js via WKWebView).
#
# WKWebView + WebGL need a real GPU, which the headless `simctl spawn` Kotlin/Native test runner
# does not provide — so we can't screenshot the 3D board from a unit test. Instead we screenshot the
# REAL app running in a booted simulator (full GPU). The app reads CHESS_START_3D and opens straight
# onto the 3D board (see MainViewController), so no human has to tap the "3D Board" toggle.
#
# Usage:  tools/ios_3d_screenshot.sh [device-name]
# Output: build/ios-3d-screenshot.png   (absolute path is printed on the last line)
set -euo pipefail

cd "$(dirname "$0")/.."
DEVICE="${1:-iPhone 17}"
BUNDLE_ID="com.example.myapplication"
SCHEME="iosApp"
DD="build/ios-dd"
OUT="$(pwd)/build/ios-3d-screenshot.png"
mkdir -p build

echo "==> Booting simulator: $DEVICE"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

echo "==> Building $SCHEME (this also rebuilds the Kotlin framework; incremental after the first run)"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme "$SCHEME" \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,name=$DEVICE" \
  -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO \
  build >/tmp/ios_3d_build.log 2>&1 || { echo "BUILD FAILED — tail of /tmp/ios_3d_build.log:"; tail -40 /tmp/ios_3d_build.log; exit 1; }

APP="$(/usr/bin/find "$DD/Build/Products" -maxdepth 2 -name '*.app' -type d | head -1)"
[ -n "$APP" ] || { echo "Could not find built .app under $DD"; exit 1; }
echo "==> Installing $APP"
xcrun simctl install "$DEVICE" "$APP"

echo "==> Launching in 3D mode"
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
SIMCTL_CHILD_CHESS_START_3D=1 xcrun simctl launch "$DEVICE" "$BUNDLE_ID" >/dev/null

echo "==> Waiting for assets + first frame"
sleep 7

echo "==> Capturing screenshot"
xcrun simctl io "$DEVICE" screenshot "$OUT" >/dev/null
echo "IOS_3D_SCREENSHOT=$OUT"
