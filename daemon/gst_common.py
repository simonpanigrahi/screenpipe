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

import gi                                  # PyGObject: the bridge that exposes C GObject libraries (like GStreamer) to Python
gi.require_version("Gst", "1.0")           # pin the GStreamer API to 1.0 BEFORE importing it, so we don't accidentally load another version
from gi.repository import Gst, GLib        # noqa: E402  -> Gst = GStreamer; GLib = the main-loop/event library GStreamer runs on


# ---------------------------------------------------------------------------
# Stream parameters. These are the Phase-1 defaults. They intentionally match
# the tablet's 16:10 aspect ratio so the test image is not distorted.
# ---------------------------------------------------------------------------
WIDTH = 1920          # the width, in pixels, every frame is scaled to before encoding (1920x1200 = high-quality 16:10)
HEIGHT = 1200         # the height, in pixels, every frame is scaled to before encoding
FPS = 60              # target frames per second the pipeline retimes to
BITRATE_KBPS = 15000  # encoder target bitrate in kbit/sec (15 Mbps). Still crisp at 1920x1200 for desktop content,
                      # but small enough that the adb-over-USB tunnel drains every frame in real time and keyframes
                      # don't cause ~1 MB spikes. The real latency lever is bounded buffers (see build_encode_tail).
GOP = FPS // 2        # keyframe every 0.5 s. Frequent keyframes make "resync to latest keyframe" (the low-latency
                      # recovery used by the sink and the Android client) snap back quickly after any drop/reconnect.
PORT = 5000           # TCP port the encoded stream is served on; the Android app reaches it via `adb reverse tcp:5000 tcp:5000`


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
_ENCODER_CANDIDATES = [          # an ordered list of (element-name, pipeline-fragment) pairs; first one that's installed wins
    # Intel low-power VA encoder — usually the lowest latency on Intel iGPUs.
    # rate-control=cbr is REQUIRED: the `va` plugin defaults to cqp (constant-QP)
    # and otherwise ignores the bitrate target, giving variable bitrate — which
    # breaks the CBR 30–60 Mbps standard and the latency budget over USB.
    ("vah264lpenc",                                                                          # element name to probe for; "lp" = low-power Intel encode path
     "vah264lpenc rate-control=cbr bitrate={kbps} ref-frames=1 b-frames=0 key-int-max={gop}"),  # cbr=constant bitrate; ref-frames=1 + b-frames=0 = no reordering delay; key-int-max=GOP

    # Generic VA-API encoder (Intel/AMD), GStreamer >= 1.22.
    ("vah264enc",                                                                            # the full-power VA-API encoder (Intel or AMD GPUs)
     "vah264enc rate-control=cbr bitrate={kbps} ref-frames=1 b-frames=0 key-int-max={gop}"),    # same low-latency tuning as the lp variant above

    # Older VA-API element (Intel/AMD), GStreamer < 1.22.
    ("vaapih264enc",                                                                                   # legacy element name from the older gstreamer-vaapi plugin
     "vaapih264enc rate-control=cbr bitrate={kbps} keyframe-period={gop} max-bframes=0"),              # note: this element spells the props differently (keyframe-period, max-bframes)

    # NVIDIA NVENC.
    ("nvh264enc",                                                                             # NVIDIA's dedicated hardware H.264 encoder
     "nvh264enc rc-mode=cbr bitrate={kbps} gop-size={gop} bframes=0 "                         # rc-mode=cbr is NVENC's name for constant bitrate; gop-size/bframes mirror the others
     "preset=low-latency-hq zerolatency=true"),                                               # preset + zerolatency=true tell NVENC to drop its internal reordering buffer

    # Pure-software fallback. Works everywhere; higher CPU + latency.
    # sliced-threads=false + threads=1 force ONE slice per frame. With tune=zerolatency
    # x264 otherwise uses sliced-threading (one slice per CPU core), so each frame becomes
    # ~N slice NALs. The Android client feeds NALs one-by-one with separate timestamps, so
    # multi-slice frames arrive as bogus partial "frames" and the decoder faults. One slice
    # per frame keeps frame == NAL, which the simple NAL-by-NAL feeder decodes correctly.
    ("x264enc",                                                                               # CPU-based H.264 encoder; always available with plugins-ugly
     "x264enc tune=zerolatency speed-preset=ultrafast bitrate={kbps} key-int-max={gop} "      # zerolatency disables look-ahead/B-frames; ultrafast trades quality for speed
     "sliced-threads=false threads=1"),                                                       # one slice per frame: a frame is a single NAL (see note above)
]


def init_gst():                            # call once at program start before building any pipeline
    """Initialise GStreamer exactly once."""
    Gst.init(None)                         # parse GStreamer's own CLI args (None = ignore argv) and set up its internals


def pick_encoder():                        # walk the candidate list and return the first encoder that's actually installed
    """
    Return (name, fragment) for the first H.264 encoder actually installed.

    `Gst.ElementFactory.find(name)` returns None if the element/plugin is not
    present, so we can probe availability without trying to instantiate.
    """
    for name, fragment in _ENCODER_CANDIDATES:                 # try candidates in best-first order
        if Gst.ElementFactory.find(name) is not None:          # find() returns a factory if the plugin is installed, else None
            return name, fragment.format(kbps=BITRATE_KBPS, gop=GOP)   # substitute {kbps}/{gop} placeholders and return the ready-to-use fragment
    raise RuntimeError(                                        # reached only if NONE of the encoders exist -> hard stop with guidance
        "No H.264 encoder found. Install VA-API/NVENC plugins, or "
        "gstreamer1.0-plugins-ugly for the x264enc software fallback."
    )


def build_encode_tail():                   # returns the common downstream half of the pipeline as a string
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
    enc_name, enc_fragment = pick_encoder()                    # decide which encoder to use right now, on this machine
    print(f"[gst] using H.264 encoder: {enc_name}")            # log it so you can SEE whether you got hardware or the software fallback

    return (                                                   # build the pipeline string piece by piece; " ! " is GStreamer's "link these elements" operator
        # INPUT leaky queue: if anything downstream (encode/scale) ever falls behind, DROP the
        # oldest RAW frames here instead of letting capture-side latency accumulate. This bounds
        # glass-to-glass latency to ~"the newest frame" regardless of momentary encoder stalls.
        "queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream ! "
        "videoconvert ! "                                      # convert whatever pixel format comes in into one the encoder accepts
        "videorate ! "                                         # duplicate/drop frames to hit a steady FPS (encoders dislike jittery input)
        "videoscale add-borders=true ! "                       # resize to the target size; add-borders=true letterboxes (black bars) instead of stretching
        f"video/x-raw,format=I420,width={WIDTH},height={HEIGHT},"  # a "capsfilter": force the exact output format after scaling...
        f"framerate={FPS}/1,pixel-aspect-ratio=1/1 ! "         # ...format=I420 (4:2:0) is REQUIRED: without it x264enc negotiates 4:4:4 (profile High 4:4:4),
                                                               # which Android hardware decoders can't decode (they fault on the SPS). Also locks res/fps/square pixels.
        f"{enc_fragment} ! "                                   # the chosen H.264 encoder fragment from pick_encoder()
        "h264parse config-interval=-1 ! "                      # tidy the encoder's output into proper H.264; -1 re-sends SPS/PPS headers before every keyframe
        "video/x-h264,stream-format=byte-stream,alignment=au ! "  # force Annex-B byte-stream (start-code delimited) and one access-unit per buffer
        "queue max-size-buffers=1 leaky=downstream ! "         # a 1-frame buffer that DROPS stale frames (leaky=downstream) -> never accumulate latency
        # OUTPUT sink. The defaults buffer per-client WITHOUT LIMIT (buffers-max=-1): a tablet that
        # drains even slightly slow would accumulate seconds of frames that never recover -> the lag
        # you saw. These options keep every client LIVE:
        #   sync=false              -> push frames immediately, no clock wait
        #   sync-method=latest-keyframe -> a new client starts at the most recent keyframe, not a backlog
        #   buffers-soft-max=30     -> if a client falls >~0.5s behind...
        #   recover-policy=keyframe -> ...drop it forward to the most recent keyframe (skip the backlog)
        f"tcpserversink host=0.0.0.0 port={PORT} sync=false "
        "sync-method=latest-keyframe recover-policy=keyframe buffers-soft-max=30"
    )


def run_pipeline(pipeline_description, on_ready=None):         # parse a full pipeline string, run it, and handle its messages
    """
    Parse and run a full pipeline string on a GLib main loop.

    `on_ready(loop)` is called once the loop is about to run, used by the daemon
    to kick off the D-Bus dance. Handles ERROR (print + quit) and EOS (quit).
    """
    print("[gst] pipeline:\n  " + pipeline_description.replace(" ! ", " !\n  "))  # pretty-print the pipeline, one element per line, for readability
    pipeline = Gst.parse_launch(pipeline_description)          # turn the text description into a live (but not yet running) pipeline object

    loop = GLib.MainLoop()                                     # an event loop that keeps the program alive and dispatches GStreamer messages

    def on_bus_message(_bus, message):                        # callback invoked for every message the pipeline posts (errors, end-of-stream, etc.)
        t = message.type                                      # what KIND of message this is
        if t == Gst.MessageType.ERROR:                        # a fatal pipeline error...
            err, debug = message.parse_error()                # unpack the human message and the verbose debug string
            print(f"[gst] ERROR: {err.message}\n[gst] debug: {debug}")  # show both so you can diagnose
            loop.quit()                                       # stop the main loop -> program ends cleanly
        elif t == Gst.MessageType.EOS:                        # EOS = end-of-stream (source finished)
            print("[gst] end-of-stream")
            loop.quit()                                       # nothing left to do -> quit
        return True                                           # True = "keep me subscribed to future messages"

    bus = pipeline.get_bus()                                  # the "bus" is the channel the pipeline posts messages onto
    bus.add_signal_watch()                                    # ask GLib to deliver bus messages to our main loop as signals
    bus.connect("message", on_bus_message)                    # wire every "message" signal to our handler above

    pipeline.set_state(Gst.State.PLAYING)                     # actually START the pipeline (data begins flowing, TCP server opens)
    print(f"[gst] PLAYING — TCP server on port {PORT}. Ctrl-C to stop.")

    if on_ready:                                              # the daemon passes a callback here; the test sender does not
        on_ready(loop)                                        # let the caller do extra setup now that the loop exists

    try:
        loop.run()                                            # BLOCK here, processing events, until loop.quit() is called
    except KeyboardInterrupt:                                 # Ctrl-C
        print("\n[gst] stopping…")
    finally:
        pipeline.set_state(Gst.State.NULL)                    # always tear the pipeline down (free the encoder, close the TCP port) on exit
