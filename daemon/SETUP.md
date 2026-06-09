# Linux dependencies

Tested target: Ubuntu 24.04 on GNOME / Wayland.

## 1. System packages (GStreamer + Python bindings + adb)

```bash
sudo apt update
sudo apt install -y \
    python3 python3-gi python3-pydbus gir1.2-gtk-3.0 \
    gstreamer1.0-tools \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-ugly \
    gstreamer1.0-pipewire \
    gstreamer1.0-libav \
    adb
```

### Hardware-encoder plugins (pick what matches your GPU)

- **Intel / AMD (VA-API):**
  ```bash
  sudo apt install -y gstreamer1.0-vaapi va-driver-all
  # verify a VA encoder exists:
  gst-inspect-1.0 vah264enc || gst-inspect-1.0 vaapih264enc
  ```
- **NVIDIA (NVENC):** install the proprietary driver, then:
  ```bash
  gst-inspect-1.0 nvh264enc
  ```
  (NVENC GStreamer support ships in `gstreamer1.0-plugins-bad` built against
  NVIDIA's SDK; if `nvh264enc` is missing you may need a newer GStreamer.)

If no hardware encoder is found, the scripts fall back to **software** `x264enc`
(`gstreamer1.0-plugins-ugly`) — it works but adds CPU load and latency.

## 2. Check your session is Wayland + GNOME

```bash
echo "$XDG_SESSION_TYPE"     # expect: wayland
echo "$XDG_CURRENT_DESKTOP"  # expect: GNOME (or ubuntu:GNOME)
```

`virtual_display_daemon.py` relies on GNOME mutter's `RecordVirtual` D-Bus API.
`test_sender.py` does **not** — it works on any setup, which is why you validate
the media path with it first.

## 3. Verify the GStreamer media path locally (optional sanity check)

This proves your encode→decode works before any networking/Android:
```bash
gst-launch-1.0 videotestsrc pattern=ball ! videoconvert ! autovideosink
```
A window with a bouncing ball = GStreamer is healthy.
