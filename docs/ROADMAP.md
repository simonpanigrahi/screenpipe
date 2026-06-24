# Roadmap

Status: **M1 achieved** — working low-latency end-to-end prototype on
GNOME/Wayland → Android over USB. M2/M4 are partially in place (aspect-fit,
auto-reconnect, daemon auto-recovery); the rest below is the path to a product.

| Milestone | Name | Scope | Status |
|-----------|------|-------|--------|
| M0 | Foundations | Repo scaffold, toolchain verified | ✅ done |
| M1 | Latency proof | Vertical slice: RecordVirtual → GStreamer H.264 → adb-reverse → MediaCodec → SurfaceView, with bounded-buffer/drop-to-live latency control | ✅ done — streams end to end, hardware decode, no codec errors |
| M2 | Scaling | Correct aspect-fit, DPI-aware scaling, resolution negotiation, multi-resolution profiles | 🟡 partial — aspect-fit done; DPI/resolution negotiation pending |
| M3 | Framing | Replace raw Annex-B stream with length-prefixed protocol; versioned handshake; multiplexed control channel (see `protocol/`) | ⬜ todo |
| M4 | Android service | Foreground service with persistent notification; survive backgrounding; auto-reconnect | 🟡 partial — auto-reconnect + daemon auto-recovery done; foreground service pending |
| M5 | Touch back-channel | Forward Android touch/stylus/keyboard events to the Linux virtual display via the control channel | ⬜ todo |
| M6 | Packaging | One-command install (`install.sh`); systemd unit for the daemon; APK signed release build | ⬜ todo |
| M7 | Polish | Settings UI (resolution, bitrate, encoder), latency overlay, error UX, documentation | 🟡 partial — control bar (Fit/Stats/Awake) done; settings UI pending |

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the detailed "areas to improve" list
that these milestones draw from.
