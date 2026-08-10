#!/usr/bin/env bash
# Android protocol-matrix benchmark, one command:
#
#   VANE_TEST_BASE_URL=https://cloudflare-quic.com VaneKotlin/bench-android.sh
#
# Builds the library's androidTest APK, installs it on the connected emulator
# (booting the first available AVD headless if nothing is connected), runs
# benchmark/ProtocolMatrixBenchmark via `am instrument`, prints the table, and
# pulls the JSON metrics to vane_benchmark/results/android-latest.json
# (override with VANE_BENCH_JSON). Knobs forwarded as instrumentation args:
# VANE_BENCH_ROUNDS / VANE_BENCH_REQUESTS / VANE_BENCH_WARMUP.
#
# `am instrument` rather than `connectedAndroidTest` because Gradle uninstalls
# the test APK when it finishes, taking the result files with it; this way the
# APK stays installed and `run-as` can read them out.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$HERE")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.inteniquetic.vanekotlin.test

BASE="${VANE_TEST_BASE_URL:-}"
case "$BASE" in
  https://*) ;;
  *)
    echo "Set VANE_TEST_BASE_URL=https://<origin serving h1.1+h2+h3>" \
      "(e.g. https://cloudflare-quic.com)" >&2
    exit 2
    ;;
esac

# Boot an emulator when nothing is connected.
if [ -z "$("$ADB" devices | awk 'NR>1 && $2=="device"')" ]; then
  AVD="$("$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | grep -v '^INFO' | head -1)"
  if [ -z "$AVD" ]; then
    echo "No device connected and no AVD available to boot." >&2
    exit 2
  fi
  echo "Booting $AVD headless..."
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
    >/dev/null 2>&1 &
  "$ADB" wait-for-device
fi
"$ADB" wait-for-device shell \
  'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'

"$HERE/gradlew" -p "$HERE" :library:installDebugAndroidTest

ARGS=(-e class com.inteniquetic.vanekotlin.benchmark.ProtocolMatrixBenchmark
      -e VANE_TEST_BASE_URL "$BASE")
for knob in VANE_BENCH_ROUNDS VANE_BENCH_REQUESTS VANE_BENCH_WARMUP; do
  v="${!knob:-}"
  [ -n "$v" ] && ARGS+=(-e "$knob" "$v")
done

# `am instrument` exits 0 even on test failure; detect it from the stream.
INSTRUMENT_OUT="$("$ADB" shell am instrument -w "${ARGS[@]}" \
  "$PKG/androidx.test.runner.AndroidJUnitRunner" | tee /dev/stderr)"
case "$INSTRUMENT_OUT" in
  *FAILURES\!\!\!*|*INSTRUMENTATION_FAILED*|*"Process crashed"*)
    echo "benchmark run FAILED (see stream above)" >&2
    exit 1
    ;;
esac

OUT_JSON="${VANE_BENCH_JSON:-$REPO_ROOT/vane_benchmark/results/android-latest.json}"
mkdir -p "$(dirname "$OUT_JSON")"
"$ADB" exec-out run-as "$PKG" cat files/vane-bench-android.json > "$OUT_JSON"
echo
"$ADB" exec-out run-as "$PKG" cat files/vane-bench-android.txt
echo
echo "metrics pulled to $OUT_JSON"
