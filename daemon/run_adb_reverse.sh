#!/usr/bin/env bash
#
# run_adb_reverse.sh
# ------------------
# Opens the USB tunnel that lets the Android app reach the Linux daemon.
#
# `adb reverse tcp:5000 tcp:5000` makes the *tablet's* localhost:5000 forward to
# the *laptop's* localhost:5000, all over the USB cable. So the Android app
# connects to 127.0.0.1:5000 and transparently reaches the GStreamer TCP server.
#
# This is the scrcpy model: rock-solid, no IP configuration, no Wi-Fi.
#
# Prerequisite: USB debugging enabled on the tablet, cable plugged in.

set -euo pipefail

PORT=5000

echo "[adb] devices:"
adb devices

# Wait until exactly one authorised device is connected.
adb wait-for-device

echo "[adb] setting up reverse tunnel tcp:${PORT} -> tcp:${PORT}"
adb reverse tcp:${PORT} tcp:${PORT}

echo "[adb] active reverse tunnels:"
adb reverse --list

echo "[adb] done. The app can now reach 127.0.0.1:${PORT} over USB."
