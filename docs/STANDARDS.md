# Quality & Latency Standards

These are the pass/fail targets for the project.  Any change that regresses a
metric below its floor is a blocking defect.

## Latency

| Metric | Target |
|--------|--------|
| Glass-to-glass (typical) | ≤ 40 ms |
| Glass-to-glass (p99) | ≤ 60 ms |
| Auto-reconnect after disconnect | < 2 s |

Measurement method: phone camera in slow-motion mode capturing both the host
screen and the tablet in a single frame; read the millisecond stopwatch delta.

## Display quality

| Metric | Target |
|--------|--------|
| Frame rate | ≥ 60 fps |
| Minimum resolution | ≥ 1920 × 1200 |
| Bitrate | 15 Mbps (CBR) over USB |

> **Bitrate note:** an earlier draft targeted 30–60 Mbps. In practice 15 Mbps CBR
> is crisp for desktop content at 1920×1200 and is the *better* latency choice:
> fatter keyframes (~1 MB) spike the USB transfer and the encoder, so a leaner
> stream drains every frame in real time. Latency is controlled by bounded
> buffers + drop-to-live, not by bitrate (see `ARCHITECTURE.md`).

## Transport

- Encoder priority: VA-API (Intel/AMD) or NVENC (NVIDIA) → software x264 fallback
- USB tunnel: adb reverse over USB-C (no Wi-Fi dependency for M1)
- The fallback to software x264 is permitted for development but must be called
  out in the run log; shipping with software encode requires a latency re-test
