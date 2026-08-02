#!/usr/bin/env bash
set -e

ITERATIONS=${1:-20}
PACKAGE="io.github.ber4444.chess"
ACTIVITY="com.example.myapplication.MainActivity"

# Clear previous results
adb shell "run-as $PACKAGE rm -f files/bench/results.jsonl" || true

# Deliver the golden set (B14). Without this the runner finds no candidates.json and silently
# falls back to two hardcoded fixtures, which produces rows that look real but score nothing.
# filesDir is app-private, so the only route in is /data/local/tmp + `run-as` (debug builds only —
# which is fine, the bench itself is gated behind isDebug in MainActivity).
GOLDEN_SRC="$(dirname "$0")/../evals/golden/candidates.json"
if [ -f "$GOLDEN_SRC" ]; then
    adb push "$GOLDEN_SRC" /data/local/tmp/candidates.json >/dev/null
    adb shell "run-as $PACKAGE mkdir -p files/golden"
    adb shell "run-as $PACKAGE sh -c 'cat /data/local/tmp/candidates.json > files/golden/candidates.json'"
    adb shell rm -f /data/local/tmp/candidates.json
    pushed=$(adb shell "run-as $PACKAGE sh -c 'wc -c < files/golden/candidates.json'" 2>/dev/null | tr -d '\r')
    if [ "${pushed:-0}" -gt 0 ] 2>/dev/null; then
        echo "Golden set delivered ($pushed bytes)."
    else
        echo "WARNING: golden set push failed — the run will use fallback fixtures (isFallbackGolden=true)." >&2
    fi
else
    echo "WARNING: $GOLDEN_SRC not found — the run will use fallback fixtures (isFallbackGolden=true)." >&2
fi

echo "Running $ITERATIONS cold-init iterations on Android..."

for i in $(seq 1 $ITERATIONS); do
    echo "Iteration $i/$ITERATIONS"
    # Force stop to ensure cold init
    adb shell am force-stop $PACKAGE
    sleep 1 # let the dust settle
    
    # Launch with bench extra. Using --ei for int extra.
    # Note: we just do 1 iteration inside the app per launch to make it a true cold init.
    adb shell am start -n $PACKAGE/$ACTIVITY --ei bench_iterations 1
    
    # Wait for the iteration to complete. Since we don't know exactly when, we poll the file
    # or just sleep generously. The bench completes in ~3-10s depending on device.
    # We will poll for the file line count.
    
    for wait in $(seq 1 30); do
        sleep 1
        lines=$(adb shell "run-as $PACKAGE cat files/bench/results.jsonl 2>/dev/null | wc -l" | tr -d '\r')
        if [ "$lines" -ge "$i" ] 2>/dev/null; then
            break
        fi
    done
done

echo "Pulling results..."
mkdir -p bench/results
adb shell "run-as $PACKAGE cat files/bench/results.jsonl" > bench/results/android_results.jsonl
echo "Done! Results saved to bench/results/android_results.jsonl"
