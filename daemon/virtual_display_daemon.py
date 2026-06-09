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

import gi
gi.require_version("Gst", "1.0")
from gi.repository import Gst, GLib  # noqa: E402

from pydbus import SessionBus  # python3-pydbus

import gst_common


# Module-level refs so the session/stream proxies are NOT garbage-collected
# (mutter tears the session down if the owning client disconnects).
_bus = None
_screencast = None
_session = None
_stream = None
_pipeline = None


def _start_gstreamer(node_id, loop):
    """Build and start the capture/encode/serve pipeline for a PipeWire node."""
    global _pipeline

    # pipewiresrc reads frames straight from the virtual monitor's PipeWire node.
    # do-timestamp=true and the leaky queue downstream keep latency minimal.
    source = (
        f"pipewiresrc path={node_id} do-timestamp=true keepalive-time=1000 ! "
        "videoconvert ! "
    )
    description = source + gst_common.build_encode_tail()

    print(f"[daemon] PipeWire node {node_id} ready — starting GStreamer")
    print("[gst] pipeline:\n  " + description.replace(" ! ", " !\n  "))

    _pipeline = Gst.parse_launch(description)

    def on_bus_message(_bus_obj, message):
        t = message.type
        if t == Gst.MessageType.ERROR:
            err, debug = message.parse_error()
            print(f"[gst] ERROR: {err.message}\n[gst] debug: {debug}")
            loop.quit()
        elif t == Gst.MessageType.EOS:
            print("[gst] end-of-stream")
            loop.quit()
        return True

    bus = _pipeline.get_bus()
    bus.add_signal_watch()
    bus.connect("message", on_bus_message)

    _pipeline.set_state(Gst.State.PLAYING)
    print(f"[gst] PLAYING — TCP server on port {gst_common.PORT}.")
    print("[daemon] A new extended display is now active. Drag a window onto it; "
          "it appears on the tablet. Ctrl-C to stop.")


def _on_pipewire_stream_added(node_id, loop):
    """Signal handler: mutter finished negotiating the virtual monitor."""
    _start_gstreamer(node_id, loop)


def _setup_virtual_monitor(loop):
    """Run the D-Bus sequence that creates the virtual monitor."""
    global _bus, _screencast, _session, _stream

    _bus = SessionBus()
    _screencast = _bus.get(
        "org.gnome.Mutter.ScreenCast", "/org/gnome/Mutter/ScreenCast"
    )

    # 1) Create a screen-cast session.
    session_path = _screencast.CreateSession({})
    _session = _bus.get("org.gnome.Mutter.ScreenCast", session_path)
    print(f"[daemon] screen-cast session: {session_path}")

    # 2) Ask for a VIRTUAL monitor.
    #    cursor-mode: 0=hidden, 1=embedded (drawn into frames), 2=metadata.
    #    Embedded is what we want so the pointer is visible on the tablet.
    #    (On GNOME 50 you may also pass virtual-monitor modes + a preferred scale
    #     here to control DPI — that's Milestone 2 / the scaling work.)
    record_virtual_props = {
        "cursor-mode": GLib.Variant("u", 1),
    }
    stream_path = _session.RecordVirtual(record_virtual_props)
    _stream = _bus.get("org.gnome.Mutter.ScreenCast", stream_path)
    print(f"[daemon] virtual stream: {stream_path}")

    # 3) Subscribe to the node-ready signal BEFORE starting, so we don't miss it.
    _stream.PipeWireStreamAdded.connect(
        lambda node_id: _on_pipewire_stream_added(node_id, loop)
    )

    # 4) Start the session. The PipeWireStreamAdded signal fires once mutter has
    #    negotiated the monitor and a PipeWire node exists.
    _session.Start()
    print("[daemon] session started — waiting for PipeWire node…")


def main():
    gst_common.init_gst()
    loop = GLib.MainLoop()

    try:
        _setup_virtual_monitor(loop)
    except Exception as exc:  # noqa: BLE001 — surface any D-Bus/API mismatch clearly
        print(f"[daemon] failed to create virtual monitor: {exc}")
        print("[daemon] Confirm you are on GNOME/Wayland and that test_sender.py "
              "works. See the VERSION SENSITIVITY note at the top of this file.")
        return

    try:
        loop.run()
    except KeyboardInterrupt:
        print("\n[daemon] stopping…")
    finally:
        if _pipeline is not None:
            _pipeline.set_state(Gst.State.NULL)
        # Closing the session removes the virtual monitor cleanly.
        try:
            if _session is not None:
                _session.Stop()
        except Exception:  # noqa: BLE001
            pass


if __name__ == "__main__":
    main()
