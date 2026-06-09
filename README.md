# USB Second-Display — Phase-1 Scaffold (Latency Proof)

This is the **vertical slice** from Milestone 1 of the roadmap: a minimal but real
end-to-end pipeline whose only job is to answer the GO/NO-GO question —
**is the glass-to-glass latency low enough (≤ ~60 ms) and the image crisp enough
to be worth building the full product?**

It is intentionally hardcoded and ugly in places. Do **not** mistake it for the
finished package. Once latency is proven, the roadmap's later milestones add the
real virtual-display sizing/scaling, a proper framed protocol, the touch
back-channel, the Android service, and one-command packaging.

```
┌─────────────────────────── LINUX (laptop) ───────────────────────────┐
│  GNOME mutter                                                          │
│   └─ RecordVirtual (D-Bus)  ──>  PipeWire node                         │
│                                     │                                  │
│   GStreamer:  pipewiresrc ─ videoconvert ─ scale ─ H.264 encode ─ ...  │
│                                     │ (Annex-B byte-stream over TCP)   │
│                                  tcpserversink :5000                   │
└─────────────────────────────────────┼─────────────────────────────────┘
                                       │  adb reverse tcp:5000 tcp:5000
                                       │  (TCP tunnelled over the USB cable)
┌─────────────────────────────────────┼──── ANDROID (Tab S9+) ──────────┐
│  StreamClient (socket) ─ splits NAL units ─> H264Decoder (MediaCodec)  │
│                                                       └─> SurfaceView   │
└────────────────────────────────────────────────────────────────────────┘
```

## What's in here

```
screenpipe/
├── README.md                     <- you are here
├── LICENSE
├── daemon/
│   ├── SETUP.md                  <- system dependencies (apt packages)
│   ├── requirements.txt          <- apt package reference (not pip)
│   ├── gst_common.py             <- shared: GStreamer init, encoder auto-detect, pipeline runner
│   ├── test_sender.py            <- STEP 1: sends a moving test pattern (NO Wayland needed)
│   ├── virtual_display_daemon.py <- STEP 2: the real thing — RecordVirtual virtual monitor
│   └── run_adb_reverse.sh        <- sets up the USB tunnel
├── android/                      <- open this folder in Android Studio
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/layout/activity_main.xml
│           ├── res/values/themes.xml
│           └── java/com/example/usbdisplay/
│               ├── MainActivity.kt   <- fullscreen surface, lifecycle
│               ├── StreamClient.kt   <- TCP read + Annex-B NAL splitting
│               └── H264Decoder.kt    <- MediaCodec hardware decode -> Surface
├── docs/
│   ├── ROADMAP.md                <- M0–M7 milestone plan
│   └── STANDARDS.md              <- latency / quality targets
└── protocol/
    └── PROTOCOL.md               <- wire-format spec (Milestone 3 placeholder)
```

## Run order (do these in sequence)

> Prerequisite: USB debugging enabled on the tablet (Developer Options), and the
> tablet plugged in via USB-C. Verify with `adb devices` (you should see it listed).

### 1. Build & install the Android app
Open `android/` in Android Studio, let it sync, run on the tablet. The app shows
"Waiting for stream…". Leave it open.

### 2. Open the USB tunnel
```bash
cd daemon
./run_adb_reverse.sh          # maps tablet localhost:5000 -> laptop localhost:5000
```

### 3a. FIRST validate transport + decode (no Wayland complexity)
```bash
python3 test_sender.py        # streams a moving ball + clock
```
You should see the moving pattern on the tablet within ~1 second. If this is
smooth, your transport + decoder + USB tunnel all work. **Get this working before
touching the daemon.** This isolates the easy 80% from the version-sensitive 20%.

### 3b. THEN run the real virtual display
```bash
python3 virtual_display_daemon.py
```
A new **extended** monitor appears (drag a window onto it — it shows on the tablet).
This uses GNOME's `RecordVirtual` D-Bus API and is the actual product core.

## Measuring latency (the GO/NO-GO test)

1. On the laptop's virtual display, open a millisecond stopwatch
   (search "online stopwatch milliseconds" in a browser, drag it to the tablet's
   extended area).
2. Point a phone camera in **slow-motion / high-fps** mode so it captures BOTH the
   laptop's own screen and the tablet in one frame.
3. Read both timers in a single captured frame; the difference is your
   glass-to-glass latency.

**GO** if typical ≤ 40 ms and worst-case ≤ 60 ms, and text is crisp.
**NO-GO** → tune before building further (see Troubleshooting). Everything
downstream is wasted effort until this gate passes.

## Troubleshooting / tuning latency

- **Encoder chosen?** Each script prints which H.264 encoder it picked. If it fell
  back to `x264enc` (software), you're missing hardware encode — install the VA-API
  / NVENC GStreamer plugins (see `daemon/SETUP.md`). Software encode adds latency + CPU.
- **Encoder property errors** (e.g. "no property named ...") — element properties
  differ across GStreamer versions. Run `gst-inspect-1.0 <encoder-name>` and adjust
  the strings in `gst_common.py`. This is expected and called out in comments.
- **Latency too high?** Lower bitrate is *not* the lever (USB has bandwidth).
  Levers: ensure hardware encode, keep the `queue ... leaky=downstream
  max-size-buffers=1` (drops stale frames), drop to 1280×800 to shrink encode time,
  confirm the tablet is decoding in low-latency mode (logged by H264Decoder).
- **`virtual_display_daemon.py` fails on RecordVirtual** — your GNOME version may
  differ. First confirm STEP 3a works (proves the media path), then see the
  version notes in that file's comments. On non-GNOME desktops this script won't
  work as-is (KDE/wlroots use different APIs — a later milestone).
- **Nothing on tablet** — check `adb reverse --list` shows the mapping, and that the
  app connects to `127.0.0.1` (it must, because of the reverse tunnel).

## Honest caveats

- This scaffold has **not** been compiled/run in your environment — it's correct,
  idiomatic code, but expect to fix small version-specific things (GStreamer
  element props, Gradle/AGP versions Android Studio wants to bump). That's normal.
- The proof renders the surface **stretched** to the SurfaceView. Correct
  aspect-fit + DPI scaling is Milestone 2 (comments mark where).
- The transport is a raw byte stream split by NAL start codes. Milestone 3 replaces
  it with length-prefixed access units (cleaner, no scanning).
