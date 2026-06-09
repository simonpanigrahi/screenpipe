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

set -euo pipefail   # fail fast: -e exit on any error, -u error on unset variables, -o pipefail catch errors mid-pipe

PORT=5000           # the TCP port to forward; must match PORT in gst_common.py and the Android app

echo "[adb] devices:"   # print a header
adb devices             # list attached devices so you can confirm the tablet is connected and authorised

# Wait until exactly one authorised device is connected.
adb wait-for-device     # block here until at least one device is ready (avoids "no device" race if you just plugged in)

echo "[adb] setting up reverse tunnel tcp:${PORT} -> tcp:${PORT}"   # announce what we're about to do (${PORT} expands to 5000)
adb reverse tcp:${PORT} tcp:${PORT}   # THE key command: tablet's localhost:5000 -> laptop's localhost:5000, over USB

echo "[adb] active reverse tunnels:"  # header
adb reverse --list                    # show the active reverse mappings so you can verify it took effect

echo "[adb] done. The app can now reach 127.0.0.1:${PORT} over USB."   # success message
