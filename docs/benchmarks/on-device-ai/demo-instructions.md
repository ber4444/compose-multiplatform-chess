# Move Coach demo — run on Samsung Android + iPhone simulator

This branch (`on-device-ai-move-coach`, checked out in worktree
`compose-multiplatform-chess-coach/`) has the on-device AI move coach wired
end-to-end. The chess app launches into 3D mode; after Stockfish returns Black's
first move, a "Move Coach" panel appears at the top of the screen.

What you'll see on each platform:

- **Android (Samsung)** — ML Kit GenAI Prompt API (Gemini Nano via AICore). On
  Samsung flagships with Android 14+ and AICore available, you'll see a real
  model explanation. On devices without AICore (older Samsungs, mid-range), the
  orchestrator deterministically falls back to rule-based text — the panel still
  appears, labelled "Coach fell back to a rule-based explanation (...)".
- **iPhone 17 simulator** — Foundation Models (Apple Intelligence). The simulator
  needs iOS 26 + Apple Intelligence enabled in `Settings > Apple Intelligence`.
  Without Apple Intelligence enabled, falls back to rule-based text. On Xcode 26+
  with Apple Intelligence on, you'll see a real model explanation.

Both fallbacks are demo-able: the panel mounts, the move-coach text appears,
and the headline + 2-sentence explanation / fallback text shows after Black's
move. The only difference is whether the explanation is model-generated or
deterministic.

## Prerequisites

- Xcode 26.x (this worktree was verified against Xcode 26.5)
- Android SDK with `platform-tools` (for `adb`)
- A booted iPhone 17 simulator (default device in `project.yml`)
- A Samsung phone with USB debugging on, connected via `adb`

## Android — Samsung phone

```bash
# From the worktree root
cd /Users/presence/AndroidStudioProjects/compose-multiplatform-chess-coach

# Build + install in one step
./gradlew :androidApp:installDebug

# Or, if you've already built (androidApp/build/outputs/apk/debug/androidApp-debug.apk):
~/Library/Android/sdk/platform-tools/adb install -r \
    androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Launch
~/Library/Android/sdk/platform-tools/adb shell am start \
    -n com.example.myapplication/.MainActivity
```

Then:
1. Tap a white piece, tap a destination square to make a move.
2. Watch Stockfish reply (Black's move animates).
3. The **Move Coach** panel slides in at the top with the explanation.

The coach is wired to **debug builds only** (`MainActivity.attachMoveCoach` checks
`ApplicationInfo.FLAG_DEBUGGABLE`); `installDebug` produces a debug APK so this
is fine. A release build would hide the coach per plan §11 M3 "ship behind a
debug flag".

If the Samsung doesn't have AICore / Gemini Nano, logcat shows
`AiAvailability.Unavailable` and the panel displays the deterministic fallback
text labelled as such.

## iOS — iPhone 17 simulator

```bash
cd /Users/presence/AndroidStudioProjects/compose-multiplatform-chess-coach

# 1. Boot the simulator
xcrun simctl boot "iPhone 17"
open -a Simulator

# 2. Build the Kotlin frameworks (Xcode does this automatically on first build,
#    but if you've changed :onDeviceAi or :app Kotlin code, run this to refresh)
./gradlew :app:linkDebugFrameworkIosSimulatorArm64 :onDeviceAi:linkDebugFrameworkIosSimulatorArm64

# 3. Build + install + launch the iOS app
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Debug \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath build/DerivedData \
    CODE_SIGNING_ALLOWED=NO build

APP_PATH="$(find build/DerivedData/Build/Products -name 'iosApp.app' -type d | head -1)"
xcrun simctl install booted "$APP_PATH"
xcrun simctl launch booted com.example.myapplication
```

Then:
1. Click a white piece, click a destination square.
2. Watch Stockfish reply.
3. The **Move Coach** panel mounts at the top with the explanation.

### Enabling Apple Intelligence in the simulator

Without Apple Intelligence enabled, Foundation Models reports `unavailable` and
the coach falls back to rule-based text. To enable:

1. In the booted simulator: **Settings > Apple Intelligence & Siri**
2. Toggle **Apple Intelligence** on (this downloads the model; first attempt
   may take a while)
3. Re-launch the chess app

If Apple Intelligence can't be enabled on the simulator (this varies by Xcode
build), the fallback text is what you'll see — still a valid demo of the
end-to-end flow.

## What's wired

- `MainActivity.attachMoveCoach()` (Android) — gates on debug builds; constructs
  `DefaultAiCoachOrchestrator` with the ML Kit Prompt factory; tracks foreground
  state via `onStart` / `onStop` so ML Kit inference is never attempted while
  backgrounded.
- `MainViewController(engine:)` (iOS) — constructs `DefaultAiCoachOrchestrator`
  with `defaultOnDeviceTextGeneratorFactory()`; the iOS factory queries
  `FoundationModelsBridgeRegistry`, which `iOSApp.swift.init` populates by
  calling `FoundationModelsBridgeRegistryKt.registerFoundationModelsProvider { ... }`
  with `FoundationMoveCoachBridge` (wrapping `FoundationMoveCoach`).
- `GameViewModel.triggerCoach(...)` — fires after Black's move is applied
  (cancellable; never blocks the move; skipped if the move ended the game).
- `MoveCoachPanel` — Compose panel mounted in both 2D and 3D layouts.

## What's NOT wired (still demo-able, but worth knowing)

- Engine evaluations are not passed to the coach request (hardcoded `null`) —
  the prompt slot exists but the data path isn't filled in. The model still
  produces an explanation from FEN + move + tags.
- ML Kit `warmup()` is never called opportunistically — first coach request
  pays the cold-init cost.
- LiteRT-LM Gemma path is a reflection-gated stub; ML Kit Prompt is always
  tried first.
