package com.example.usbdisplay                 // same app package

import android.media.MediaCodec                 // Android's low-level hardware codec API (we use it as an H.264 DECODER)
import android.media.MediaFormat                // describes the video format (mime, width, height, flags) we hand the codec
import android.os.Build                         // lets us check the device's Android version at runtime
import android.util.Log                         // logcat logging
import android.view.Surface                     // the on-screen surface the decoder draws decoded frames onto
import java.util.concurrent.LinkedBlockingQueue // a thread-safe FIFO queue used to pass work between threads

/**
 * Wraps a hardware H.264 (AVC) decoder. Decoded frames are rendered DIRECTLY onto
 * the provided [Surface] (zero-copy), which is the low-latency path on Android.
 *
 * Design:
 *  - We run MediaCodec in ASYNC mode (callbacks) rather than polling, which is the
 *    recommended low-latency style.
 *  - Two queues decouple the network thread from the codec:
 *      pendingNals          : NAL units arriving from [StreamClient]
 *      availableInputBuffers: input buffer indices the codec has handed back
 *    A small feeder thread marries the two: take a NAL, take a free buffer, submit.
 *  - SPS (NAL type 7) and PPS (NAL type 8) are submitted with BUFFER_FLAG_CODEC_CONFIG
 *    so the decoder configures itself from the stream. The Linux side sends these
 *    before every keyframe (h264parse config-interval=-1), so connecting mid-stream
 *    or after a reconnect recovers quickly.
 *
 * NOTE: feeding NAL-by-NAL is the simple Phase-1 approach and works on Android's
 * AVC decoders. Milestone 3 switches to length-prefixed access units.
 */
class H264Decoder(                              // constructor params -> private fields:
    private val surface: Surface,               // where decoded frames are rendered (from the SurfaceView)
    private val onStatus: (String) -> Unit,      // callback to report status to the UI
    private val onResolution: (Int, Int) -> Unit = { _, _ -> }   // callback with the real decoded width/height (for aspect-fit + stats)
) {
    companion object {                          // shared constants ("static")
        private const val TAG = "H264Decoder"   // logcat tag
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc" = H.264; tells the system which decoder to load
        // Nominal configure size. The decoder reads the true dimensions from the
        // stream's SPS, so this only needs to be a sane placeholder. It matches the
        // resolution the Linux side forces (1920x1200).
        private const val NOMINAL_WIDTH = 1920  // placeholder width used only until the real size arrives in the SPS
        private const val NOMINAL_HEIGHT = 1200 // placeholder height (same reason)
        // Latency guard: at ~1 NAL per frame, this many queued NALs means we've fallen
        // ~0.75 s behind live. Rather than render stale frames, we dump the backlog and
        // resync at the next keyframe. Keeps the displayed image at "now".
        private const val MAX_PENDING_NALS = 45
    }

    private var codec: MediaCodec? = null       // the actual decoder instance; null until start()
    @Volatile private var running = false       // on/off switch, read by the feeder thread (@Volatile = cross-thread safe)
    @Volatile private var sawConfig = false     // have we fed the decoder its first SPS yet? (gates picture data until configured;
                                                // @Volatile because the network thread can reset it on a backlog drop, see submitNal)

    private val pendingNals = LinkedBlockingQueue<ByteArray>()       // NALs waiting to be fed in (produced by StreamClient, consumed by the feeder)
    private val availableInputBuffers = LinkedBlockingQueue<Int>()   // indices of empty input buffers the codec gave us (produced by the codec callback)
    private var feeder: Thread? = null          // the thread that marries NALs to free buffers and submits them

    /** Start the decoder and begin rendering to the surface. */
    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, NOMINAL_WIDTH, NOMINAL_HEIGHT)   // describe the stream: H.264 at the placeholder size

        // Ask the decoder for low-latency mode (skip frame reordering buffers).
        // KEY_LOW_LATENCY is API 30+. The Tab S9+ is far above this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {   // R = Android 11 (API 30); only set the flag if supported
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)   // 1 = enable low-latency decode (no output reordering buffer = less delay)
        }

        val c = MediaCodec.createDecoderByType(MIME)   // create a decoder that can handle "video/avc"

        // Async callbacks — must be set BEFORE configure().
        c.setCallback(object : MediaCodec.Callback() {   // register callbacks so the codec PUSHES events to us (async mode) instead of us polling
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {   // codec: "I have an empty input slot #index, give me data"
                // The codec is ready for more input; hand the index to the feeder.
                availableInputBuffers.offer(index)   // queue the index for the feeder thread to use
            }

            override fun onOutputBufferAvailable(   // codec: "decoded frame #index is ready"
                mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo
            ) {
                // render=true draws the frame onto the Surface immediately.
                try {
                    mc.releaseOutputBuffer(index, true)   // release the output buffer with render=true -> the frame is drawn to the Surface (zero-copy)
                } catch (e: IllegalStateException) {      // can happen if the codec was torn down mid-callback
                    Log.w(TAG, "releaseOutputBuffer failed", e)
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {   // fires once the decoder learns the REAL dimensions from the SPS
                val w = format.getInteger(MediaFormat.KEY_WIDTH)    // actual decoded width
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)   // actual decoded height
                Log.i(TAG, "decoding ${w}x$h")
                onResolution(w, h)   // report the true dimensions so MainActivity can aspect-fit the surface + show stats
                onStatus("") // clear the status overlay once frames flow   // empty string -> MainActivity hides the "Waiting…" label
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {   // the codec hit a fatal error
                Log.e(TAG, "codec error", e)
                onStatus("decoder error: ${e.message}")   // surface it to the UI
            }
        })

        // null crypto, flags=0 (decoder). Render target is our Surface.
        c.configure(format, surface, null, 0)   // configure the codec: format, render onto `surface`, no encryption (null), flags 0 = decoder
        c.start()                               // start it -> the codec begins handing us empty input buffers via the callback
        codec = c                               // store the instance
        sawConfig = false                       // we haven't fed the SPS yet; drop picture data until we do
        running = true                          // arm the feeder loop

        feeder = Thread({ feedLoop() }, "h264-feeder").apply { start() }   // spawn the feeder thread that submits NALs into the codec
        Log.i(TAG, "decoder started")
    }

    /** Called by the network thread for each NAL unit (includes the start code). */
    fun submitNal(nal: ByteArray) {             // StreamClient calls this for every complete NAL it parses
        if (!running) return                    // not active -> ignore
        if (pendingNals.size >= MAX_PENDING_NALS) {   // we're falling behind live (decode/render slower than arrival)...
            // Drop the whole backlog and resync at the next keyframe. Feeding the stale frames
            // would just render seconds-old content; skipping to the next SPS/keyframe (resent
            // every GOP by the Linux side) snaps the display back to "now".
            pendingNals.clear()                 // discard the stale frames
            sawConfig = false                   // make the feeder wait for the next SPS before decoding again
            Log.w(TAG, "input backlog >= $MAX_PENDING_NALS NALs — dropping to resync (latency guard)")
        }
        pendingNals.offer(nal)                  // queue this NAL for the feeder (offer never blocks)
    }

    /**
     * Retarget the running decoder to a new render Surface.
     *
     * A SurfaceView hands out a fresh/reallocated Surface whenever it is resized
     * (e.g. our aspect-fit relayout once the real video size is known). If we keep
     * rendering to the stale Surface, MediaCodec faults with UNKNOWN_ERROR. Calling
     * setOutputSurface() while the codec is running is the supported way to follow
     * the SurfaceView to its new Surface without tearing the decoder down.
     */
    fun setSurface(newSurface: Surface) {
        if (!newSurface.isValid) return         // nothing to point at yet; ignore
        try {
            codec?.setOutputSurface(newSurface) // hot-swap the render target; keeps the decode pipeline alive
        } catch (e: IllegalStateException) {    // codec not in a state that allows the swap (e.g. mid-teardown)
            Log.w(TAG, "setOutputSurface failed", e)
        }
    }

    private fun feedLoop() {                     // runs on the feeder thread: pull a NAL + a free buffer, submit to the codec
        val c = codec ?: return                 // grab the codec; bail if somehow null
        var ptsUs = 0L                           // a fake, monotonically increasing presentation timestamp (microseconds)
        try {
            while (running) {                    // loop until stop()
                // Block until we have a NAL.
                val nal = pendingNals.take()     // BLOCK until a NAL is available (from submitNal)
                val type = nalType(nal)          // the H.264 NAL unit type (7=SPS, 8=PPS, 5=IDR, 1=non-IDR, …)

                // Connecting mid-stream, the first NALs are P-slices that reference frames
                // we never saw. Feeding picture data to a codec that has no parameter sets
                // yet faults it (UNKNOWN_ERROR) before the first keyframe arrives. So drop
                // everything until the first SPS; the Linux side resends SPS/PPS before every
                // keyframe (h264parse config-interval=-1), so the wait is at most one GOP.
                if (!sawConfig) {                // not configured yet?
                    if (type != 7) continue      // ignore anything that isn't an SPS
                    sawConfig = true             // SPS in hand -> from here we feed the codec
                }

                val index = availableInputBuffers.take()   // BLOCK until the codec offers a free input buffer

                val input = c.getInputBuffer(index) ?: continue   // get the actual ByteBuffer for that slot; skip if null
                input.clear()                    // reset the buffer's position/limit so we can write from the start
                if (nal.size > input.capacity()) {   // does the NAL fit in the codec's input buffer?
                    // A NAL larger than the codec's input buffer would throw
                    // BufferOverflowException (a RuntimeException not caught below)
                    // and silently kill this thread. Recycle the buffer empty and
                    // drop the NAL; the next keyframe recovers the stream.
                    Log.w(TAG, "NAL ${nal.size}B exceeds input buffer ${input.capacity()}B; dropping")
                    c.queueInputBuffer(index, 0, 0, 0, 0)   // submit an EMPTY buffer to recycle the slot (otherwise the index is leaked)
                    continue                     // skip this oversized NAL and move on
                }
                input.put(nal)                   // copy the NAL bytes into the codec's input buffer

                val flags = if (type == 7 || type == 8) {   // is this NAL an SPS/PPS header rather than picture data?
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG   // yes -> mark it as codec-config so the decoder configures itself from it
                } else {
                    0                            // no -> normal frame data, no special flag
                }
                // Monotonic, increasing timestamps keep the decoder happy; exact
                // values don't matter because we render on arrival.
                ptsUs += 1000L                   // bump the fake timestamp by 1000 µs each NAL (just needs to keep increasing)
                c.queueInputBuffer(index, 0, nal.size, ptsUs, flags)   // hand the filled buffer back to the codec: offset 0, length nal.size, timestamp, flags
            }
        } catch (_: InterruptedException) {      // thrown by take() when stop() interrupts us
            // normal on stop()
        } catch (e: IllegalStateException) {     // thrown if the codec was torn down while we were mid-call
            Log.w(TAG, "feed loop ended", e)
        }
    }

    /** The H.264 NAL unit type (low 5 bits of the header byte), or -1 if unparseable. */
    private fun nalType(nal: ByteArray): Int {
        // Skip the Annex-B start code to reach the NAL header byte.
        val headerIndex = when {                 // figure out how long the leading start code is, so we can find the NAL header byte after it
            nal.size >= 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                nal[2].toInt() == 0 && nal[3].toInt() == 1 -> 4   // 4-byte start code 00 00 00 01 -> header is at index 4
            nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                nal[2].toInt() == 1 -> 3                          // 3-byte start code 00 00 01 -> header is at index 3
            else -> return -1                    // no recognisable start code -> unknown
        }
        if (headerIndex >= nal.size) return -1   // guard: the NAL is just a start code with no header byte
        return nal[headerIndex].toInt() and 0x1F // the low 5 bits of the NAL header byte = the NAL unit type (7=SPS, 8=PPS, …)
    }

    fun stop() {                                 // shut the decoder down cleanly
        running = false                          // stop the feeder loop
        feeder?.interrupt()                      // wake the feeder if it's blocked in take()
        // Join the feeder BEFORE releasing the codec, otherwise it can call
        // getInputBuffer/queueInputBuffer on a released codec (use-after-release).
        try { feeder?.join(500) } catch (_: InterruptedException) {}   // wait up to 500 ms for the feeder thread to actually exit
        feeder = null                            // drop the reference
        try {
            codec?.stop()                        // stop the codec (may throw if already in a bad state)
        } catch (_: IllegalStateException) {
        }
        codec?.release()                         // free the codec's hardware resources
        codec = null                             // drop the reference
        pendingNals.clear()                      // discard any queued NALs
        availableInputBuffers.clear()            // discard any queued buffer indices
        Log.i(TAG, "decoder stopped")
    }
}
