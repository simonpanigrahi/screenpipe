package com.example.usbdisplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

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
class H264Decoder(
    private val surface: Surface,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"
        // Nominal configure size. The decoder reads the true dimensions from the
        // stream's SPS, so this only needs to be a sane placeholder. It matches the
        // resolution the Linux side forces (1920x1200).
        private const val NOMINAL_WIDTH = 1920
        private const val NOMINAL_HEIGHT = 1200
    }

    private var codec: MediaCodec? = null
    @Volatile private var running = false

    private val pendingNals = LinkedBlockingQueue<ByteArray>()
    private val availableInputBuffers = LinkedBlockingQueue<Int>()
    private var feeder: Thread? = null

    /** Start the decoder and begin rendering to the surface. */
    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, NOMINAL_WIDTH, NOMINAL_HEIGHT)

        // Ask the decoder for low-latency mode (skip frame reordering buffers).
        // KEY_LOW_LATENCY is API 30+. The Tab S9+ is far above this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        val c = MediaCodec.createDecoderByType(MIME)

        // Async callbacks — must be set BEFORE configure().
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                // The codec is ready for more input; hand the index to the feeder.
                availableInputBuffers.offer(index)
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo
            ) {
                // render=true draws the frame onto the Surface immediately.
                try {
                    mc.releaseOutputBuffer(index, true)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "releaseOutputBuffer failed", e)
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                Log.i(TAG, "decoding ${w}x$h")
                onStatus("") // clear the status overlay once frames flow
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "codec error", e)
                onStatus("decoder error: ${e.message}")
            }
        })

        // null crypto, flags=0 (decoder). Render target is our Surface.
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        running = true

        feeder = Thread({ feedLoop() }, "h264-feeder").apply { start() }
        Log.i(TAG, "decoder started")
    }

    /** Called by the network thread for each NAL unit (includes the start code). */
    fun submitNal(nal: ByteArray) {
        if (running) pendingNals.offer(nal)
    }

    private fun feedLoop() {
        val c = codec ?: return
        var ptsUs = 0L
        try {
            while (running) {
                // Block until we have both a NAL and a free input buffer.
                val nal = pendingNals.take()
                val index = availableInputBuffers.take()

                val input = c.getInputBuffer(index) ?: continue
                input.clear()
                input.put(nal)

                val flags = if (isParameterSet(nal)) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    0
                }
                // Monotonic, increasing timestamps keep the decoder happy; exact
                // values don't matter because we render on arrival.
                ptsUs += 1000L
                c.queueInputBuffer(index, 0, nal.size, ptsUs, flags)
            }
        } catch (_: InterruptedException) {
            // normal on stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "feed loop ended", e)
        }
    }

    /** True if this NAL unit is an SPS (7) or PPS (8) parameter set. */
    private fun isParameterSet(nal: ByteArray): Boolean {
        // Skip the Annex-B start code to reach the NAL header byte.
        val headerIndex = when {
            nal.size >= 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                nal[2].toInt() == 0 && nal[3].toInt() == 1 -> 4   // 00 00 00 01
            nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                nal[2].toInt() == 1 -> 3                          // 00 00 01
            else -> return false
        }
        if (headerIndex >= nal.size) return false
        val type = nal[headerIndex].toInt() and 0x1F
        return type == 7 || type == 8
    }

    fun stop() {
        running = false
        feeder?.interrupt()
        feeder = null
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
        }
        codec?.release()
        codec = null
        pendingNals.clear()
        availableInputBuffers.clear()
        Log.i(TAG, "decoder stopped")
    }
}
