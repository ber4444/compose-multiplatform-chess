# On-Device Benchmark Harness

This document outlines the architecture and usage of the on-device AI benchmark harness, which is used to measure performance metrics (init time, time to first token, generation speed, memory usage) of the on-device models for the Move Coach feature across Android and iOS.

## Overview

The benchmark harness bypasses the UI and directly instruments the `DefaultAiCoachOrchestrator` using a `BenchProbe` interface. This allows us to gather timing metrics without UI overhead.

Metrics collected:
- **Init Time (`initEndMs - initStartMs`)**: The time taken to load the model into memory.
- **Time to First Token (`firstTokenMs - generateStartMs`)**: The time taken to process the prompt and return the first word.
- **Tokens per Second (`tokenCount / (completeMs - firstTokenMs)`)**: The average generation speed.
- **Peak Memory Usage**: Native heap size for Android.
- **Thermal Status**: Android thermal status before and after generation.

**Note**: You must run benchmarks on physical devices, not emulators/simulators, to get meaningful performance numbers.

## Running the Benchmarks

### Android

1. Connect a physical Android device.
2. Ensure the device is awake and unlocked.
3. Run the automated script from the root of the project. This script force-stops the app between iterations to measure "cold start" initialization times correctly.

```bash
# Run 20 iterations (default)
./bench/run_android.sh

# Run 50 iterations
./bench/run_android.sh 50
```

The script will pull the results to `bench/results/android_results.jsonl`.

### iOS

1. Open `iosApp/iosApp.xcodeproj` in Xcode.
2. Select a physical iOS device as the run destination.
3. Run the `iosAppUITests` target (`Cmd+U` or via the Test navigator).

The XCUITest runner will repeatedly launch the app with the `BENCHMARK_MODE` environment variable, triggering the internal `IosBenchRunner`. Detailed memory and clock metrics can also be observed through Xcode Instruments if configured in the test scheme. The JSONL results will be written to the app's document directory.

## Generating the Report

A Python script is provided to aggregate the JSONL output into a Markdown table showing p50, p90, and p99 metrics.

1. Ensure Python 3 is installed.
2. Run the report script against the generated results file:

```bash
python3 bench/report.py bench/results/android_results.jsonl
```

The script will output a formatted Markdown table suitable for PR descriptions or documentation.
