# Architecture

How the USB second-display works end to end, why it's built this way, what the
trade-offs are, and where the remaining latency lives.

## The one-paragraph version

GNOME mutter creates a **virtual monitor** (a real extended display with no
hardware behind it) and exposes its frames as a **PipeWire** node. GStreamer
captures that node, scales it to 1920×1200 4:2:0, encodes it to **H.264** tuned
for zero latency, and serves the raw Annex-B byte stream on **TCP :5000**. That
port is tunnelled over the **USB cable** with `adb reverse`, so the Android app
connects to its own `127.0.0.1:5000` and transparently reaches the host. On the
tablet, a thread reads the byte stream, splits it into NAL units, and feeds them
to a hardware **MediaCodec** decoder that renders straight onto a `SurfaceView`.

## Data flow

```
 mutter RecordVirtual ─ creates ─▶ virtual monitor ─ exposes ─▶ PipeWire node
                                                                     │
   ┌─────────────────────────── GStreamer (host) ───────────────────┘
   │  pipewiresrc do-timestamp=true
   │    → queue(leaky, 2)            drop stale RAW frames if encode stalls
   │    → videoconvert → videorate → videoscale(add-borders)
   │    → caps: I420, 1920x1200, 60fps   (I420 = 4:2:0 = Baseline-decodable)
   │    → x264enc tune=zerolatency, 1 slice, GOP=0.5s, 15 Mbps
   │    → h264parse config-interval=-1   (resend SPS/PPS before every keyframe)
   │    → caps: byte-stream, au-aligned
   │    → queue(leaky, 1)            keep only the freshest encoded AU
   │    → tcpserversink :5000  sync=false  recover-policy=keyframe  soft-max=30
   └────────────────────────────────┬──────────────────────────────────────────
                                     │  adb reverse tcp:5000  (over USB)
   ┌──────────────────────── Android app ───────────────────────────────────────
   │  StreamClient   socket(127.0.0.1:5000, tcpNoDelay) → scan Annex-B start codes
   │                 → emit one NAL per callback, auto-reconnect every 1s
   │  H264Decoder    bounded NAL queue (≤45 ≈ 0.75s) → drop+resync on overflow
   │                 → SPS gate (wait for first SPS before feeding pictures)
   │                 → MediaCodec async, KEY_LOW_LATENCY, render=true (zero-copy)
   │  MainActivity   SurfaceView, aspect-fit on real decoded size, control bar
   └─────────────────────────────────────────────────────────────────────────────
```

## Why each major choice

**Virtual monitor via `RecordVirtual` (not a mirror).** GNOME's ScreenCast
`RecordVirtual` is the same mechanism `gnome-remote-desktop` uses for headless
sessions. It gives a genuine *extended* desktop the compositor treats as real —
windows can live there — instead of duplicating the laptop screen.

**GStreamer for the media path.** Every stage we need already exists as a tuned
element (`pipewiresrc`, hardware encoders, `h264parse`, `tcpserversink`). Writing
our own capture/encode/transport would be months of work for a worse result.

**H.264 (AVC), Baseline / 4:2:0.** Universally hardware-decodable on Android. The
pipeline *forces* `format=I420` (4:2:0): left to negotiate, x264 picks 4:4:4
(profile High 4:4:4 / `profile_idc=244`), which Qualcomm's `c2.qti.avc.decoder`
cannot decode — it faults on the SPS. This was the single hardest bug to find.

**USB transport via `adb reverse` (the scrcpy model).** No IP config, no Wi-Fi,
no pairing — the cable *is* the network. Rock-solid and inherently low-jitter.

**TCP, not UDP/RTP.** Over a USB cable, loss is effectively zero and bandwidth is
huge, so TCP's reliability costs us nothing while removing the need for a
jitter-buffer / NACK / FEC machinery. Latency is controlled by *dropping* at the
endpoints (below), not by a transport-level buffer.

**MediaCodec async + render-on-arrival.** Async (callback) mode is the
recommended low-latency style; `releaseOutputBuffer(index, true)` renders
zero-copy directly to the Surface, and `KEY_LOW_LATENCY` asks the decoder to skip
its output-reorder buffer.

## The latency model: drop, don't buffer

The core principle: **at every stage, prefer the newest frame and discard
anything stale.** A single accumulating buffer anywhere turns into seconds of lag
that never recovers (this was the original symptom). The bounded points:

| Where | Bound | What happens on overflow |
|-------|-------|--------------------------|
| Host input queue | 2 frames, `leaky=downstream` | drop oldest raw frame |
| Host output queue | 1 AU, `leaky=downstream` | drop oldest encoded frame |
| `tcpserversink` per client | `buffers-soft-max=30`, `recover-policy=keyframe` | skip client forward to newest keyframe |
| Android NAL queue | 45 NALs ≈ 0.75 s | clear backlog, resync at next keyframe |

Frequent keyframes (GOP = 0.5 s) are what make "resync to latest keyframe" cheap:
the worst-case wait after any drop is half a second, usually much less.

### Approximate latency budget (software encode, 1920×1200@60)

| Stage | Typical |
|-------|---------|
| Capture + convert/scale | ~2–5 ms |
| x264 `ultrafast zerolatency` encode | ~8–15 ms (software; HW encode ~2–4 ms) |
| USB transfer of one AU @15 Mbps | ~1–2 ms |
| MediaCodec decode | ~5–10 ms |
| Compose + panel scan-out (120 Hz) | ~8–16 ms |
| **Total (glass-to-glass)** | **~25–50 ms** |

The two biggest movable costs are **software encode** and the **decoder's output
reorder depth** (Qualcomm reports `max output delay 18` — with Baseline/no
B-frames it shouldn't hold all 18, but pinning it tighter is a known lever).

## Pros

- **Genuinely low latency** by design — bounded buffers + drop-to-live keep the
  display at "now," unlike Wi-Fi display's deep buffering.
- **Wired reliability** — no Wi-Fi congestion, no pairing, no IP setup; the cable
  is deterministic and high-bandwidth.
- **Real extended desktop**, not a mirror — the compositor treats it as a monitor.
- **Hardware decode** on the tablet — low power, no thermal throttling, frees CPU.
- **Self-healing** — daemon auto-recovers from node drops; app auto-reconnects;
  SPS resent every GOP so a mid-stream join recovers within ≤0.5 s.
- **Small, legible codebase** — standard elements, heavily commented, no exotic deps.

## Cons / limitations

- **GNOME/Wayland only** — `RecordVirtual` is mutter-specific; KDE/wlroots/X11
  need different capture backends.
- **Display-only** — no touch/keyboard back-channel yet; the tablet can't drive
  the host.
- **Software encode on the dev box** — higher CPU and a few ms more latency than
  VA-API/NVENC (auto-detect prefers hardware when the plugins are present).
- **Fragile transport framing** — raw Annex-B split by start-code scanning; no
  length prefixes, timestamps, or versioned handshake on the wire.
- **One client, one fixed resolution** — 1920×1200 is hardcoded; no DPI/scale
  negotiation or multi-tablet support.
- **Version-sensitive** — GStreamer element properties and the ScreenCast D-Bus
  signature vary across distro versions and occasionally need adjustment.

## Areas to improve (ranked by latency-per-effort)

1. **Enable a hardware encoder (VA-API / NVENC).** Biggest, lowest-effort win:
   cuts encode latency from ~10 ms to ~3 ms and offloads the CPU entirely. The
   auto-detect already prefers it — it's purely an install (`gstreamer1.0-vaapi`
   + `va-driver-all`, or the NVENC plugin). Re-measure after enabling.

2. **Pin the decoder output-reorder depth.** Investigate forcing
   `max-output-buffer-count` / vendor low-latency keys so the Qualcomm decoder
   emits each frame immediately instead of allowing a deep output queue.

3. **Framed transport protocol** (`protocol/PROTOCOL.md`). Length-prefixed access
   units remove start-code scanning, carry a capture timestamp (enables real
   end-to-end latency telemetry), and multiplex a control channel — the
   foundation for everything below.

4. **Touch + input back-channel.** Forward tablet touch/stylus/keyboard events
   over the control channel into the virtual display (mutter's RemoteDesktop API),
   turning it from a monitor into an interactive surface.

5. **Resolution & DPI negotiation.** Read the tablet's real panel size/density at
   connect and ask `RecordVirtual` for a matching mode + scale, instead of the
   hardcoded 1920×1200. Add bitrate/resolution profiles for different links.

6. **Adaptive bitrate / dynamic keyframes.** Watch the socket send queue; on
   sustained backpressure, drop bitrate or request a keyframe rather than relying
   solely on endpoint dropping. Mostly relevant if/when a Wi-Fi path is added.

7. **Android foreground service + lifecycle.** Survive backgrounding and screen
   rotation, persistent notification, robust surface re-acquisition — so it's a
   real app, not a single Activity.

8. **Packaging.** `install.sh`, a systemd user unit for the daemon, a signed
   release APK, and a one-command bring-up so it's usable without this README.

9. **Multi-platform capture.** Abstract the capture backend so KDE
   (`xdg-desktop-portal-kde`), wlroots, and X11 can plug in behind the same
   encode/serve tail.

## Failure handling (what already self-heals)

- **PipeWire node drop / capture EOS** → daemon tears down the pipeline + session
  and recreates the virtual monitor with capped exponential backoff
  (`virtual_display_daemon.py`).
- **TCP disconnect** → `StreamClient` retries every second.
- **Mid-stream join** → SPS/PPS resent before every keyframe; the decoder's SPS
  gate drops picture data until the first SPS, so it configures cleanly.
- **Client falls behind** → host drops it forward to the latest keyframe; the
  Android NAL queue dumps its backlog and resyncs. Either way the display snaps
  back to live rather than playing catch-up.
