# USB Second-Display

Turn an Android tablet into a **low-latency wired second monitor** for a
GNOME/Wayland Linux laptop. The host creates a *real extended display* (not a
mirror), encodes it to H.264, and streams it over the **USB cable** (no Wi-Fi) to
the tablet, where a hardware decoder renders it to the screen.

The whole point of going wired is **latency**: Wi-Fi display (Miracast and
friends) buffers hundreds of milliseconds and feels laggy. This pipeline targets
glass-to-glass latency low enough that the cursor tracks your hand in real time.

```
┌─────────────────────────── LINUX (laptop) ───────────────────────────┐
│  GNOME mutter                                                          │
│   └─ RecordVirtual (D-Bus)  ──>  PipeWire node  (a virtual monitor)    │
│                                     │                                  │
│   GStreamer:  pipewiresrc ─ convert ─ scale(I420) ─ H.264 ─ h264parse │
│                                     │ (Annex-B byte-stream over TCP)   │
│                                  tcpserversink :5000  (drop-to-live)   │
└─────────────────────────────────────┼─────────────────────────────────┘
                                       │  adb reverse tcp:5000 tcp:5000
                                       │  (TCP tunnelled over the USB cable)
┌─────────────────────────────────────┼──── ANDROID (tablet) ───────────┐
│  StreamClient (socket) ─ splits NAL units ─> H264Decoder (MediaCodec)  │
│         bounded backlog, keyframe-resync   └─> SurfaceView (aspect-fit) │
└────────────────────────────────────────────────────────────────────────┘
```

## Status

A **working end-to-end prototype.** Verified on Ubuntu 24.04 (GNOME/Wayland,
Ryzen 9 5900HX) → Samsung tablet over USB:

- ✅ Real extended virtual monitor via GNOME `RecordVirtual` (drag a window onto it)
- ✅ Hardware H.264 decode on the tablet (`c2.qti.avc.decoder`), zero codec errors
- ✅ Aspect-fit rendering + on-screen control bar (Connect/Fit/Stats/Keep-awake)
- ✅ Bounded-buffer, drop-to-live latency control end to end
- ✅ Daemon auto-recovers if the PipeWire capture node drops

Currently using the **software x264 encoder** (no VA-API/NVENC plugins installed
on the dev machine). See [Areas to improve](#areas-to-improve) and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the latency analysis and the
hardware-encode upgrade path.

## Repository layout

```
screenpipe/
├── README.md                     <- you are here
├── LICENSE
├── daemon/                       <- the Linux host side (Python + GStreamer)
│   ├── SETUP.md                  <- system + python dependencies
│   ├── requirements.txt          <- apt package reference
│   ├── gst_common.py             <- shared: GStreamer init, encoder auto-detect, encode tail
│   ├── test_sender.py            <- sends a moving test pattern (NO Wayland needed)
│   ├── virtual_display_daemon.py <- the real thing: RecordVirtual virtual monitor + auto-recovery
│   └── run_adb_reverse.sh        <- sets up the USB tunnel
├── android/                      <- the tablet app (Kotlin)
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── res/...               <- layout + theme
│       └── java/com/example/usbdisplay/
│           ├── MainActivity.kt   <- surface, lifecycle, control bar, aspect-fit
│           ├── StreamClient.kt   <- TCP read + Annex-B NAL splitting, auto-reconnect
│           └── H264Decoder.kt    <- MediaCodec hardware decode -> Surface, latency guard
├── docs/
│   ├── ARCHITECTURE.md           <- how it works, pros/cons, latency budget, improvement areas
│   ├── ROADMAP.md                <- M0–M7 milestone plan
│   └── STANDARDS.md              <- latency / quality targets
└── protocol/
    └── PROTOCOL.md               <- framed wire-format spec (future milestone)
```

## Prerequisites

- **Linux host:** Ubuntu 24.04 on **GNOME / Wayland** (the virtual display uses
  mutter's `RecordVirtual` D-Bus API). Install the dependencies in
  [`daemon/SETUP.md`](daemon/SETUP.md).
- **Tablet:** Android 11+ with **USB debugging** enabled (Developer Options),
  plugged in via USB-C. Confirm with `adb devices` (it should be listed).
- **Android build tools:** either Android Studio, or just the command-line SDK
  (see [Build the app](#1-build--install-the-android-app) — no GUI needed).

## Run it

### 1. Build & install the Android app

**Terminal only** (no Android Studio). With the SDK command-line tools installed,
point Gradle at the SDK and build:

```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # tell Gradle where the SDK is
./gradlew assembleDebug                                # build the debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.usbdisplay/.MainActivity
```

(Or open `android/` in Android Studio and Run.) The app shows "Waiting for
stream…" and auto-connects once the host is streaming.

### 2. Open the USB tunnel

```bash
cd daemon
./run_adb_reverse.sh          # maps tablet localhost:5000 -> laptop localhost:5000
```

### 3a. First validate transport + decode (no Wayland complexity)

```bash
python3 test_sender.py        # streams a moving ball + clock
```

The pattern should appear on the tablet within ~1 second. This proves the
transport + decoder + USB tunnel all work, isolating the easy 80% from the
version-sensitive virtual-display 20%. **Get this working first.**

### 3b. Then run the real virtual display

```bash
python3 virtual_display_daemon.py
```

A new **extended** monitor appears — drag a window onto it and it shows on the
tablet. This uses GNOME's `RecordVirtual` D-Bus API and is the product core. If
the PipeWire capture node ever drops, the daemon tears down and recreates the
monitor automatically (capped backoff); the app reconnects on its own.

## How latency is kept low

Latency in a streaming pipeline is dominated by **buffering**, not raw encode
speed. The design keeps every stage at "show the newest frame, drop the rest":

| Stage | Mechanism |
|-------|-----------|
| Capture | `pipewiresrc do-timestamp=true` + a 2-frame `leaky=downstream` input queue |
| Encode | x264 `tune=zerolatency` (no B-frames/look-ahead), single slice, 0.5 s GOP |
| Serve | `tcpserversink sync=false`, `recover-policy=keyframe`, `buffers-soft-max=30` — a slow client is dropped *forward* to the latest keyframe instead of accumulating a backlog |
| Network | `adb reverse` over USB, `tcpNoDelay` on the socket |
| Decode | MediaCodec `KEY_LOW_LATENCY`, async mode, zero-copy render to Surface |
| Client backlog | NAL queue bounded to ~0.75 s; on overflow, dump and resync at the next keyframe |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full latency budget and
where the remaining milliseconds live.

## Measuring latency (the GO/NO-GO test)

1. Open a millisecond stopwatch on the host and drag it onto the tablet's
   extended area (so the *same* timer shows on both screens).
2. Point a phone camera in **slow-motion / high-fps** mode so it captures BOTH
   the laptop screen and the tablet in one frame.
3. Read both timers in a single captured frame; the difference is your
   glass-to-glass latency.

Targets: typical ≤ 40 ms, worst-case ≤ 60 ms, text crisp (see
[`docs/STANDARDS.md`](docs/STANDARDS.md)).

## Troubleshooting

- **Which encoder am I on?** The daemon prints `[gst] using H.264 encoder: …`.
  `x264enc` = software (works, but more CPU/latency). For hardware, install the
  VA-API / NVENC plugins (see `daemon/SETUP.md`) and rerun.
- **Encoder property errors** ("no property named …") — element properties drift
  across GStreamer versions. Run `gst-inspect-1.0 <encoder>` and adjust the
  strings in `gst_common.py`. This is expected and called out in comments.
- **`virtual_display_daemon.py` fails on `RecordVirtual`** — your GNOME version
  may differ. First confirm step 3a works (proves the media path), then see the
  VERSION SENSITIVITY note at the top of that file. Non-GNOME desktops (KDE,
  wlroots) use different APIs and won't work as-is.
- **Nothing on the tablet** — check `adb reverse --list` shows the mapping and
  that the app connects to `127.0.0.1` (it must, because of the reverse tunnel).
- **Decoder faults on connect** — the stream must be 4:2:0 (Baseline-decodable);
  `gst_common.py` forces `format=I420` for exactly this reason. Don't remove it.

## Known limitations

- **Software encode** on the dev machine adds CPU load and a few ms of encode
  latency vs a hardware encoder. The auto-detect prefers VA-API/NVENC when present.
- **Display-only** — no touch back-channel yet (the tablet can't drive the host).
- **Raw Annex-B transport** split by NAL start codes; a length-prefixed framed
  protocol (`protocol/PROTOCOL.md`) is a later milestone.
- **GNOME/Wayland only** for the virtual display.

## Areas to improve

Short version (full analysis in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)):

1. **Hardware encoder** (VA-API/NVENC) — lowest-effort latency + CPU win.
2. **Cap the decoder's output reorder depth** — Qualcomm decoders report a deep
   output buffer; pinning it tighter shaves decode-side latency.
3. **Framed protocol** — length-prefixed access units remove start-code scanning
   and let us carry timestamps/control on one channel.
4. **Touch + input back-channel** — make it a true interactive display.
5. **One-command packaging** — `install.sh`, signed release APK, systemd unit.
