package com.example.usbdisplay

import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connects to the Linux GStreamer TCP server (reached at 127.0.0.1 via the
 * adb-reverse USB tunnel), reads the raw H.264 byte stream, splits it into
 * Annex-B NAL units, and hands each one to [onNal].
 *
 * Why split here? TCP is a byte stream with no message boundaries, so we must
 * re-find NAL boundaries ourselves using the Annex-B start code (00 00 01). A NAL
 * is considered complete when we see the START of the *next* one — this adds at
 * most one NAL of latency (sub-frame), which is negligible.
 *
 * Robustness handled:
 *  - A NAL may span several TCP reads -> we accumulate into a growable buffer.
 *  - Start codes may straddle a read boundary -> the buffer is only compacted up
 *    to the last confirmed NAL boundary, never mid-pattern.
 *  - Connection drops / server-not-ready -> retry every second.
 *
 * Milestone 3 replaces this scanning with length-prefixed access units.
 */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val onNal: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "StreamClient"
        private const val READ_CHUNK = 32 * 1024
        private const val INITIAL_BUFFER = 256 * 1024
        private const val RETRY_MS = 1000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null

    fun start() {
        running = true
        thread = Thread({ connectLoop() }, "stream-client").apply { start() }
    }

    private fun connectLoop() {
        while (running) {
            try {
                onStatus("Connecting to $host:$port…")
                val s = Socket()
                s.tcpNoDelay = true                          // disable Nagle -> lower latency
                s.connect(InetSocketAddress(host, port), 3000)
                socket = s
                onStatus("Connected")
                Log.i(TAG, "connected to $host:$port")
                readStream(s.getInputStream())
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "connection lost: ${e.message}; retrying")
                    onStatus("Reconnecting…")
                    Thread.sleep(RETRY_MS)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
    }

    /** Read bytes, extract complete NAL units, emit each via [onNal]. */
    private fun readStream(input: InputStream) {
        var buffer = ByteArray(INITIAL_BUFFER)
        var len = 0                                          // valid bytes in buffer
        val chunk = ByteArray(READ_CHUNK)

        while (running) {
            val n = input.read(chunk)
            if (n < 0) throw RuntimeException("stream ended")
            if (n == 0) continue

            // Grow the buffer if needed, then append the new bytes.
            if (len + n > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, len + n))
            }
            System.arraycopy(chunk, 0, buffer, len, n)
            len += n

            // Find the first start code; anything before it is junk we discard.
            var nalStart = findStartCode(buffer, 0, len)
            if (nalStart < 0) {
                // No start code yet; if junk is piling up, drop it but keep a small
                // tail in case a start code straddles the boundary.
                if (len > READ_CHUNK) {
                    val keep = 3
                    System.arraycopy(buffer, len - keep, buffer, 0, keep)
                    len = keep
                }
                continue
            }

            // Emit every NAL for which we can see the NEXT start code.
            while (true) {
                val nextStart = findStartCode(buffer, nalStart + 3, len)
                if (nextStart < 0) break                     // current NAL not complete yet
                val nal = buffer.copyOfRange(nalStart, nextStart)
                onNal(nal)
                nalStart = nextStart
            }

            // Compact: move the unconsumed remainder (the in-progress NAL) to front.
            if (nalStart > 0) {
                System.arraycopy(buffer, nalStart, buffer, 0, len - nalStart)
                len -= nalStart
            }
        }
    }

    /**
     * Return the index of the next Annex-B start code (00 00 01) at or after
     * [from], scanning up to [limit]; -1 if none. A leading extra 00 (the 4-byte
     * 00 00 00 01 form) simply ends up as a harmless trailing zero on the prior
     * NAL — Android's decoder accepts that.
     */
    private fun findStartCode(buf: ByteArray, from: Int, limit: Int): Int {
        var i = maxOf(from, 0)
        while (i + 2 < limit) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                return i
            }
            i++
        }
        return -1
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
    }
}
