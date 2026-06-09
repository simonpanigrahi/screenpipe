"""
gst_common.py
=============
Shared helpers used by both `test_sender.py` and `virtual_display_daemon.py`.

Responsibilities:
  1. Initialise GStreamer.
  2. Auto-detect the best available H.264 encoder (Intel/AMD VA-API, NVIDIA NVENC,
     or a software fallback) so the project runs on any laptop without the user
     having to choose.
  3. Provide the *shared tail* of the pipeline (the part after the raw-video
     source): scale -> encode -> parse -> TCP server. Both the test pattern and
     the real virtual display feed into this same tail.
  4. Run a pipeline string on a GLib main loop with basic error/EOS handling.

Why GStreamer? Every stage we need already exists as a tuned element
(pipewiresrc, hardware encoders, h264parse, tcpserversink). Writing our own
encoder/transport from scratch would be months of work for a worse result.
"""

import gi
gi.require_version("Gst", "1.0")
from gi.repository import Gst, GLib  # noqa: E402  (must come after require_version)


# ---------------------------------------------------------------------------
# Stream parameters. These are the Phase-1 defaults. They intentionally match
# the tablet's 16:10 aspect ratio so the test image is not distorted.
# ---------------------------------------------------------------------------
WIDTH = 1920          # streamed width  (1920x1200 = high-quality 16:10 default)
HEIGHT = 1200         # streamed height
FPS = 60              # frames per second
BITRATE_KBPS = 40000  # 40 Mbps — visually lossless over USB; bitrate is NOT the
                      # latency lever, so we keep it high for crisp text.
GOP = FPS             # keyframe every ~1 second -> fast reconnection recovery.
PORT = 5000           # TCP port; the Android app reaches it via `adb reverse`.


# ---------------------------------------------------------------------------
# Encoder auto-detection.
#
# We list encoders best-first. For each we give a GStreamer fragment tuned for
# LOW LATENCY (no B-frames, single reference frame, CBR, short GOP).
#
# IMPORTANT: element property names drift between GStreamer versions. If a script
# errors with "no property named X", run `gst-inspect-1.0 <encoder>` and adjust
# the matching string below. This is expected, not a bug.
# ---------------------------------------------------------------------------
_ENCODER_CANDIDATES = [
    # Intel low-power VA encoder — usually the lowest latency on Intel iGPUs.
    ("vah264lpenc",
     "vah264lpenc bitrate={kbps} ref-frames=1 b-frames=0 key-int-max={gop}"),

    # Generic VA-API encoder (Intel/AMD), GStreamer >= 1.22.
    ("vah264enc",
     "vah264enc bitrate={kbps} ref-frames=1 b-frames=0 key-int-max={gop}"),

    # Older VA-API element (Intel/AMD), GStreamer < 1.22.
    ("vaapih264enc",
     "vaapih264enc rate-control=cbr bitrate={kbps} keyframe-period={gop} max-bframes=0"),

    # NVIDIA NVENC.
    ("nvh264enc",
     "nvh264enc rc-mode=cbr bitrate={kbps} gop-size={gop} bframes=0 "
     "preset=low-latency-hq zerolatency=true"),

    # Pure-software fallback. Works everywhere; higher CPU + latency.
    ("x264enc",
     "x264enc tune=zerolatency speed-preset=ultrafast bitrate={kbps} key-int-max={gop}"),
]


def init_gst():
    """Initialise GStreamer exactly once."""
    Gst.init(None)


def pick_encoder():
    """
    Return (name, fragment) for the first H.264 encoder actually installed.

    `Gst.ElementFactory.find(name)` returns None if the element/plugin is not
    present, so we can probe availability without trying to instantiate.
    """
    for name, fragment in _ENCODER_CANDIDATES:
        if Gst.ElementFactory.find(name) is not None:
            return name, fragment.format(kbps=BITRATE_KBPS, gop=GOP)
    raise RuntimeError(
        "No H.264 encoder found. Install VA-API/NVENC plugins, or "
        "gstreamer1.0-plugins-ugly for the x264enc software fallback."
    )


def build_encode_tail():
    """
    The shared pipeline tail, fed by a raw-video source upstream.

    Stage by stage:
      videoconvert      - normalise pixel format the encoder accepts
      videorate         - retime to a constant FPS
      videoscale        - resize to WIDTHxHEIGHT; add-borders=true LETTERBOXES
                          instead of stretching, preserving aspect ratio
      capsfilter        - lock resolution/fps/square-pixels
      <encoder>         - hardware (or software) H.264, low-latency tuned
      h264parse         - config-interval=-1 re-sends SPS/PPS before every
                          keyframe, so a client connecting mid-stream (or after a
                          reconnect) can configure its decoder quickly
      capsfilter        - byte-stream (Annex-B, start-code delimited) + au aligned
      queue             - max-size-buffers=1 + leaky=downstream: never buffer
                          stale frames; drop them. This is critical for latency.
      tcpserversink     - serves the byte stream to whoever connects on PORT;
                          sync=false avoids clock-based buffering delay
    """
    enc_name, enc_fragment = pick_encoder()
    print(f"[gst] using H.264 encoder: {enc_name}")

    return (
        "videoconvert ! "
        "videorate ! "
        "videoscale add-borders=true ! "
        f"video/x-raw,width={WIDTH},height={HEIGHT},"
        f"framerate={FPS}/1,pixel-aspect-ratio=1/1 ! "
        f"{enc_fragment} ! "
        "h264parse config-interval=-1 ! "
        "video/x-h264,stream-format=byte-stream,alignment=au ! "
        "queue max-size-buffers=1 leaky=downstream ! "
        f"tcpserversink host=0.0.0.0 port={PORT} sync=false"
    )


def run_pipeline(pipeline_description, on_ready=None):
    """
    Parse and run a full pipeline string on a GLib main loop.

    `on_ready(loop)` is called once the loop is about to run, used by the daemon
    to kick off the D-Bus dance. Handles ERROR (print + quit) and EOS (quit).
    """
    print("[gst] pipeline:\n  " + pipeline_description.replace(" ! ", " !\n  "))
    pipeline = Gst.parse_launch(pipeline_description)

    loop = GLib.MainLoop()

    def on_bus_message(_bus, message):
        t = message.type
        if t == Gst.MessageType.ERROR:
            err, debug = message.parse_error()
            print(f"[gst] ERROR: {err.message}\n[gst] debug: {debug}")
            loop.quit()
        elif t == Gst.MessageType.EOS:
            print("[gst] end-of-stream")
            loop.quit()
        return True

    bus = pipeline.get_bus()
    bus.add_signal_watch()
    bus.connect("message", on_bus_message)

    pipeline.set_state(Gst.State.PLAYING)
    print(f"[gst] PLAYING — TCP server on port {PORT}. Ctrl-C to stop.")

    if on_ready:
        on_ready(loop)

    try:
        loop.run()
    except KeyboardInterrupt:
        print("\n[gst] stopping…")
    finally:
        pipeline.set_state(Gst.State.NULL)
