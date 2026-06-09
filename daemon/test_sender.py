#!/usr/bin/env python3
"""
test_sender.py  —  STEP 1 (run this BEFORE the real daemon)
===========================================================
Streams a synthetic moving test pattern to the tablet. It deliberately uses NO
Wayland, NO D-Bus, and NO virtual display — just `videotestsrc`. That isolates
the easy, reliable 80% of the system (encode -> TCP -> USB tunnel -> Android
decode -> render) from the version-sensitive 20% (creating a virtual monitor).

If the bouncing ball + clock show up smoothly on the tablet, then your encoder,
the adb-reverse tunnel, and the Android client/decoder all work. Only then move
on to `virtual_display_daemon.py`.

Run:
    ./run_adb_reverse.sh      # in another terminal, once
    python3 test_sender.py
"""

import gi
gi.require_version("Gst", "1.0")
from gi.repository import Gst  # noqa: E402

import gst_common


def main():
    gst_common.init_gst()

    # Source: a bouncing ball (motion lets you judge latency/smoothness) with a
    # running clock overlay. `is-live=true` makes it behave like a real capture.
    source = (
        "videotestsrc is-live=true pattern=ball ! "
        f"video/x-raw,width={gst_common.WIDTH},height={gst_common.HEIGHT},"
        f"framerate={gst_common.FPS}/1 ! "
        "timeoverlay halignment=right valignment=bottom font-desc=\"Sans 36\" ! "
    )

    # Source feeds straight into the shared encode/serve tail.
    pipeline = source + gst_common.build_encode_tail()
    gst_common.run_pipeline(pipeline)


if __name__ == "__main__":
    main()
