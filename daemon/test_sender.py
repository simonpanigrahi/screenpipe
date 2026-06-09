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

import gi                                  # PyGObject bridge (same as in gst_common) so we can talk to GStreamer
gi.require_version("Gst", "1.0")           # pin GStreamer to the 1.0 API before importing it
from gi.repository import Gst              # noqa: E402  -> the GStreamer module itself (imported but mostly used via gst_common here)

import gst_common                          # our shared helpers: init, encoder picking, the encode tail, and the run loop


def main():                                # program entry point
    gst_common.init_gst()                  # initialise GStreamer once (must happen before building any pipeline)

    # Source: a bouncing ball (motion lets you judge latency/smoothness) with a
    # running clock overlay. `is-live=true` makes it behave like a real capture.
    source = (                                                                 # build the UPSTREAM half (the fake video source) as a string
        "videotestsrc is-live=true pattern=ball ! "                            # generate test video; pattern=ball is a moving ball; is-live=true mimics a real camera/screen timing
        f"video/x-raw,width={gst_common.WIDTH},height={gst_common.HEIGHT},"    # capsfilter: ask the source for our target resolution...
        f"framerate={gst_common.FPS}/1 ! "                                     # ...and our target framerate
        "timeoverlay halignment=right valignment=bottom font-desc=\"Sans 36\" ! "  # burn a running clock into the bottom-right corner — used to measure latency on camera
    )

    # Source feeds straight into the shared encode/serve tail.
    pipeline = source + gst_common.build_encode_tail()   # glue the fake source onto the SAME encode->TCP tail the real daemon uses
    gst_common.run_pipeline(pipeline)                    # parse, start, and block on the pipeline until Ctrl-C / error / EOS


if __name__ == "__main__":                 # only run main() when executed directly (not when imported as a module)
    main()
