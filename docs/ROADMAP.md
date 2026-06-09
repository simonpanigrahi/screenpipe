# Roadmap

| Milestone | Name | Scope |
|-----------|------|-------|
| M0 | Foundations | Repo scaffold, toolchain verified, CI skeleton |
| M1 | Latency proof | Vertical slice: RecordVirtual → GStreamer H.264 → adb-reverse → MediaCodec → SurfaceView. GO/NO-GO gate: ≤ 40 ms typical, ≤ 60 ms p99 |
| M2 | Scaling | Correct aspect-fit, DPI-aware scaling, resolution negotiation, multi-resolution profiles |
| M3 | Framing | Replace raw Annex-B stream with length-prefixed protocol; versioned handshake; multiplexed control channel (see `protocol/`) |
| M4 | Android service | Foreground service with persistent notification; survive app backgrounding; auto-reconnect |
| M5 | Touch back-channel | Forward Android touch events to the Linux virtual display via the control channel |
| M6 | Packaging | One-command install (`install.sh`); systemd unit for the daemon; APK signed release build |
| M7 | Polish | Settings UI (resolution, bitrate, encoder), latency overlay, error UX, documentation |
