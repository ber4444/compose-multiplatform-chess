#!/usr/bin/env bash
set -e

ITERATIONS=${1:-20}
PACKAGE="io.github.ber4444.chess"
ACTIVITY="com.example.myapplication.MainActivity"

# Clear previous results
adb shell "run-as $PACKAGE rm -f files/bench/results.jsonl" || true

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
