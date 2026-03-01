#!/usr/bin/env bash
# Script to start adb logcat processes for each connected device.
# Logs are filtered by the application's package name and written to
# individual files under the `logs/` directory named after the device ID.
# This script will keep running in a loop, spawning new logcats when
# devices appear.  Kill it with Ctrl-C or by terminating the process.

APP_ID="dev.wads.motoridecallconnect"

# base dir is one level up from this script (assumes script lives in `scripts/`)
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$BASE_DIR/logs"

# diagnostics
echo "script path: ${BASH_SOURCE[0]}"
echo "working directory: $(pwd)"
echo "log directory: $LOG_DIR"

mkdir -p "$LOG_DIR"

# kill any previous helper logcat processes so we start fresh
echo "cleaning up old logcat processes..."
pkill -f "adb -s .* logcat" >/dev/null 2>&1 || true

start_logcat() {
    local dev="$1"
    local outfile="$LOG_DIR/${dev}.log"

    # ensure the file exists even if no log lines appear yet
    touch "$outfile"

    # avoid spawning duplicate logcat processes for the same device
    if pgrep -f "adb -s $dev logcat" >/dev/null; then
        echo "  logcat already running for $dev, skipping"
        return
    fi

    # resolve the UID of the application package for native logcat filtering
    local pkg_uid
    pkg_uid=$(adb -s "$dev" shell cmd package list packages -U "$APP_ID" 2>/dev/null \
              | grep -oE 'uid:[0-9]+' | cut -d: -f2)

    if [[ -z "$pkg_uid" ]]; then
        echo "  WARNING: could not resolve UID for $APP_ID on $dev (app not installed?), skipping"
        return
    fi

    echo "Starting logcat for device $dev (UID $pkg_uid) -> $outfile"
    # use logcat's native --uid filter so we capture ALL lines from the app,
    # not just those that happen to contain the package name string.
    adb -s "$dev" logcat --uid="$pkg_uid" >>"$outfile" 2>&1 &
}

# handle devices currently connected

echo "Scanning for currently connected devices..."
for dev in $(adb devices | awk 'NR>1 && $1!="" {print $1}'); do
    echo "  found device: $dev"
    start_logcat "$dev"
done

echo "Initial device scan complete."

# continuously watch for new devices and start logcat for them
while true; do
    sleep 5
    echo "[`date '+%Y-%m-%d %H:%M:%S'`] checking for devices..."
    devices=$(adb devices | awk 'NR>1 && $1!="" {print $1}')
    if [[ -z "$devices" ]]; then
        echo "  no devices currently connected"
    else
        for dev in $devices; do
            echo "  device detected: $dev"
            start_logcat "$dev"
        done
    fi
done
