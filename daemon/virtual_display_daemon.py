#!/usr/bin/env python3
"""
virtual_display_daemon.py  —  STEP 2 (the real product core)
============================================================
Creates a genuine EXTENDED monitor on GNOME/Wayland that isn't backed by any
physical hardware, captures it, encodes it, and serves it over TCP — exactly the
same tail the test sender uses.

How it works (the D-Bus dance):
  1. Talk to GNOME mutter's screen-cast service: org.gnome.Mutter.ScreenCast.
  2. CreateSession() -> a session object path.
  3. On that session, call RecordVirtual(): this asks mutter to create a *virtual
     monitor*. Crucially, the monitor's size/refresh are negotiated by PipeWire,
     so the monitor doesn't exist until negotiation completes.
  4. The resulting Stream object emits a `PipeWireStreamAdded` signal carrying a
     PipeWire node id.
  5. We hand that node id to GStreamer's `pipewiresrc path=<id>` and start the
     same encode -> TCP pipeline.

This is the same mechanism gnome-remote-desktop uses for headless/virtual
screens, which is why it can create a true extended desktop (not a mirror).

-----------------------------------------------------------------------------
VERSION SENSITIVITY — READ THIS
-----------------------------------------------------------------------------
The ScreenCast D-Bus API has evolved across GNOME versions:
  * The RecordVirtual method and PipeWireStreamAdded signal exist from GNOME 40+.
  * GNOME 50 added passing predefined virtual-monitor *modes* and a *preferred
    scale* to RecordVirtual (for DPI/scaling control — used in a later milestone).
If RecordVirtual rejects the properties below, run:
    gdbus introspect --session --dest org.gnome.Mutter.ScreenCast \
        --object-path /org/gnome/Mutter/ScreenCast
to see the exact signature your GNOME exposes, and adjust `record_virtual_props`.

Always confirm `test_sender.py` works first — that proves the media path, so any
failure here is isolated to the virtual-display creation.
"""

import gi                                  # PyGObject bridge to GStreamer + GLib
gi.require_version("Gst", "1.0")           # pin GStreamer API version before import
from gi.repository import Gst, GLib        # noqa: E402  -> Gst = GStreamer; GLib gives us the main loop and Variant (typed D-Bus values)

from pydbus import SessionBus              # python3-pydbus: a friendly wrapper for talking to D-Bus services (here, GNOME mutter)

import gst_common                          # our shared init / encode-tail / pipeline helpers


# Module-level refs so the session/stream proxies are NOT garbage-collected
# (mutter tears the session down if the owning client disconnects).
_bus = None                                # the D-Bus connection object
_screencast = None                         # proxy to org.gnome.Mutter.ScreenCast (the top-level service)
_session = None                            # proxy to the screen-cast session we create
_stream = None                             # proxy to the virtual-monitor stream within that session
_pipeline = None                           # the GStreamer pipeline (created later, once we have a PipeWire node id)
_recovering = False                        # guard so overlapping errors don't stack multiple recovery attempts
_retry_delay = 1                           # seconds to wait before recreating the monitor; backs off on repeated failure (reset on success)


def _start_gstreamer(node_id, loop):       # called once mutter hands us a PipeWire node to capture from
    """Build and start the capture/encode/serve pipeline for a PipeWire node."""
    global _pipeline                       # we assign the module-level _pipeline so it survives after this function returns

    # pipewiresrc reads frames straight from the virtual monitor's PipeWire node.
    # do-timestamp=true and the leaky queue downstream keep latency minimal.
    source = (                                                                 # build the UPSTREAM half: capture from the virtual monitor
        f"pipewiresrc path={node_id} do-timestamp=true keepalive-time=1000 ! " # read frames from PipeWire node <id>; do-timestamp stamps arrival time; keepalive avoids stalls when idle
        "videoconvert ! "                                                      # normalise the captured pixel format before it hits the encode tail
    )
    description = source + gst_common.build_encode_tail()   # append the SAME encode->TCP tail the test sender uses

    print(f"[daemon] PipeWire node {node_id} ready — starting GStreamer")
    print("[gst] pipeline:\n  " + description.replace(" ! ", " !\n  "))   # pretty-print the pipeline one element per line

    _pipeline = Gst.parse_launch(description)               # turn the text into a live pipeline object

    def on_bus_message(_bus_obj, message):                  # handle messages this pipeline posts (errors / end-of-stream)
        t = message.type                                    # the message kind
        if t == Gst.MessageType.ERROR:                      # pipeline error -> usually the PipeWire node went away ("target not found")
            err, debug = message.parse_error()              # unpack readable message + verbose debug
            print(f"[gst] ERROR: {err.message}\n[gst] debug: {debug}")
            _schedule_recovery(loop)                        # recreate the virtual monitor instead of exiting
        elif t == Gst.MessageType.EOS:                      # end-of-stream -> the capture ended; treat like a drop and rebuild
            print("[gst] end-of-stream")
            _schedule_recovery(loop)
        return True                                         # stay subscribed to future messages

    bus = _pipeline.get_bus()                               # the pipeline's message channel
    bus.add_signal_watch()                                  # deliver its messages into our GLib main loop
    bus.connect("message", on_bus_message)                  # route every message to the handler above

    _pipeline.set_state(Gst.State.PLAYING)                  # START capturing/encoding/serving
    global _retry_delay                                     # streaming actually started...
    _retry_delay = 1                                        # ...so reset the recovery backoff to its baseline
    print(f"[gst] PLAYING — TCP server on port {gst_common.PORT}.")
    print("[daemon] A new extended display is now active. Drag a window onto it; "
          "it appears on the tablet. Ctrl-C to stop.")


def _teardown(stop_session):               # release the pipeline (and optionally the screen-cast session) before rebuilding
    """Tear down the GStreamer pipeline and, if asked, the mutter session."""
    global _pipeline, _session, _stream
    if _pipeline is not None:                              # if a pipeline exists...
        try:
            _pipeline.set_state(Gst.State.NULL)            # ...stop it and FREE the encoder + release TCP port 5000
        except Exception:                                  # noqa: BLE001
            pass
        _pipeline = None
    if stop_session:                                       # on a node drop the old session is dead; close it so mutter cleans up
        try:
            if _session is not None:
                _session.Stop()                            # removes the (now-defunct) virtual monitor
        except Exception:                                  # noqa: BLE001 — it may already be gone
            pass
        _session = None
        _stream = None


def _schedule_recovery(loop):              # called from the bus handler when the stream drops; rebuilds after a short delay
    """Tear the dead stream down and schedule recreation of the virtual monitor."""
    global _recovering
    if _recovering:                                        # a recovery is already pending -> don't stack another
        return
    _recovering = True
    _teardown(stop_session=True)                           # drop the dead pipeline + session (frees port 5000 before we rebind it)
    print(f"[daemon] virtual display lost — recreating in {_retry_delay}s…")
    GLib.timeout_add_seconds(_retry_delay, _recover, loop) # run _recover once after the backoff delay (on the main loop)


def _recover(loop):                        # the actual rebuild, run from a GLib timeout (so it's on the main thread)
    """Recreate the virtual monitor; on failure, back off and try again."""
    global _recovering, _retry_delay
    _recovering = False                                    # this attempt is now running; allow a future one to be scheduled
    try:
        _setup_virtual_monitor(loop)                       # fresh session + RecordVirtual + Start -> fires PipeWireStreamAdded -> _start_gstreamer
    except Exception as exc:                               # noqa: BLE001 — D-Bus/mutter may be momentarily unavailable
        _retry_delay = min(_retry_delay * 2, 5)            # exponential backoff, capped at 5s, so we don't spin
        print(f"[daemon] recovery failed: {exc}; retrying in {_retry_delay}s")
        _schedule_recovery(loop)                           # try again later
    return False                                           # False -> this is a one-shot timeout, don't repeat


def _on_pipewire_stream_added(node_id, loop):               # D-Bus signal handler: fires when mutter finishes building the virtual monitor
    """Signal handler: mutter finished negotiating the virtual monitor."""
    _start_gstreamer(node_id, loop)                         # now that a node exists, build and run the GStreamer pipeline on it


def _setup_virtual_monitor(loop):          # run the whole D-Bus sequence that asks mutter for a virtual monitor
    """Run the D-Bus sequence that creates the virtual monitor."""
    global _bus, _screencast, _session, _stream            # store all proxies at module scope so they aren't garbage-collected

    _bus = SessionBus()                                    # connect to the user's session D-Bus (where GNOME services live)
    _screencast = _bus.get(                                # get a proxy object for mutter's ScreenCast service...
        "org.gnome.Mutter.ScreenCast", "/org/gnome/Mutter/ScreenCast"   # ...by its well-known bus name and object path
    )

    # 1) Create a screen-cast session.
    session_path = _screencast.CreateSession({})           # ask mutter to open a new session; {} = no special options; returns the session's object path
    _session = _bus.get("org.gnome.Mutter.ScreenCast", session_path)   # get a proxy for that specific session object
    print(f"[daemon] screen-cast session: {session_path}")

    # 2) Ask for a VIRTUAL monitor.
    #    cursor-mode: 0=hidden, 1=embedded (drawn into frames), 2=metadata.
    #    Embedded is what we want so the pointer is visible on the tablet.
    #    (On GNOME 50 you may also pass virtual-monitor modes + a preferred scale
    #     here to control DPI — that's Milestone 2 / the scaling work.)
    record_virtual_props = {                               # the options dict we pass to RecordVirtual
        "cursor-mode": GLib.Variant("u", 1),              # GLib.Variant("u", 1) = a D-Bus uint32 with value 1 = "embedded" (draw the cursor into the video)
    }
    stream_path = _session.RecordVirtual(record_virtual_props)   # THE key call: create a virtual monitor; returns the stream object's path
    _stream = _bus.get("org.gnome.Mutter.ScreenCast", stream_path)   # get a proxy for that stream object
    print(f"[daemon] virtual stream: {stream_path}")

    # 3) Subscribe to the node-ready signal BEFORE starting, so we don't miss it.
    _stream.PipeWireStreamAdded.connect(                  # subscribe to the "stream is ready" signal...
        lambda node_id: _on_pipewire_stream_added(node_id, loop)   # ...the signal carries the PipeWire node id; we forward it (plus loop) to our handler
    )

    # 4) Start the session. The PipeWireStreamAdded signal fires once mutter has
    #    negotiated the monitor and a PipeWire node exists.
    _session.Start()                                      # tell mutter to actually begin -> triggers monitor creation -> fires the signal above
    print("[daemon] session started — waiting for PipeWire node…")


def main():                                # program entry point
    gst_common.init_gst()                  # initialise GStreamer once
    loop = GLib.MainLoop()                 # the event loop that keeps the daemon alive and delivers D-Bus signals + GStreamer messages

    try:
        _setup_virtual_monitor(loop)       # kick off the D-Bus dance (this returns immediately; the work finishes via the signal callback)
    except Exception as exc:               # noqa: BLE001 — surface any D-Bus/API mismatch clearly
        print(f"[daemon] failed to create virtual monitor: {exc}")            # most failures here are GNOME-version/API mismatches
        print("[daemon] Confirm you are on GNOME/Wayland and that test_sender.py "
              "works. See the VERSION SENSITIVITY note at the top of this file.")
        return                             # bail out before entering the loop if setup failed

    try:
        loop.run()                         # BLOCK here, handling signals/messages, until something calls loop.quit()
    except KeyboardInterrupt:              # Ctrl-C
        print("\n[daemon] stopping…")
    finally:
        if _pipeline is not None:          # if a pipeline was created...
            _pipeline.set_state(Gst.State.NULL)   # ...tear it down (free encoder, close TCP port)
        # Closing the session removes the virtual monitor cleanly.
        try:
            if _session is not None:       # if a session exists...
                _session.Stop()            # ...stop it, which removes the virtual monitor from your desktop
        except Exception:                  # noqa: BLE001  -> ignore errors during shutdown (we're exiting anyway)
            pass


if __name__ == "__main__":                 # run main() only when executed directly, not when imported
    main()
