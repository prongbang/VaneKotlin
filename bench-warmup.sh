#!/usr/bin/env bash
# warmup() cold-start benchmark, one command:
#
#   VANE_TEST_BASE_URL=https://cloudflare-quic.com VaneKotlin/bench-warmup.sh
#
# Cold start is a once-per-process fact (trust-store/conscrypt init is
# process-global), so each WarmupBenchmark test runs in its own
# `am instrument` invocation — a fresh app process — VANE_BENCH_REPEATS
# times (default 3). Results accumulate in the app's
# files/vane-warmup-bench.txt and are printed at the end.
#
# Companion to bench-android.sh (which owns the full protocol matrix and the
# unwarmed `cold` column); same emulator-boot and result-pull mechanics.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.inteniquetic.vanekotlin.test
CLASS=com.inteniquetic.vanekotlin.benchmark.WarmupBenchmark

BASE="${VANE_TEST_BASE_URL:-}"
case "$BASE" in
  https://*) ;;
  *)
    echo "Set VANE_TEST_BASE_URL=https://<origin> (e.g. https://cloudflare-quic.com)" >&2
    exit 2
    ;;
esac
REPEATS="${VANE_BENCH_REPEATS:-3}"

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

"$ADB" shell run-as "$PKG" rm -f files/vane-warmup-bench.txt >/dev/null 2>&1 || true

for test in tcpCold tcpColdAfterWarmup h3Cold h3ColdAfterWarmup; do
  for i in $(seq 1 "$REPEATS"); do
    echo "== $test run $i/$REPEATS =="
    # `am instrument` exits 0 even on test failure; detect it from the stream.
    OUT="$("$ADB" shell am instrument -w \
      -e class "$CLASS#$test" \
      -e VANE_TEST_BASE_URL "$BASE" \
      "$PKG/androidx.test.runner.AndroidJUnitRunner")"
    case "$OUT" in
      *FAILURES\!\!\!*|*INSTRUMENTATION_FAILED*|*"Process crashed"*)
        printf '%s\n' "$OUT" >&2
        echo "warmup benchmark run FAILED" >&2
        exit 1
        ;;
    esac
  done
done

echo
"$ADB" exec-out run-as "$PKG" cat files/vane-warmup-bench.txt
