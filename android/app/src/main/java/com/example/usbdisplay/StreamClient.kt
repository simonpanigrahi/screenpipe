package com.example.usbdisplay                 // same package as the rest of the app

import android.util.Log                         // Android's logcat logging
import java.io.InputStream                      // the byte stream we read H.264 from
import java.net.InetSocketAddress               // a host:port pair used to connect the socket
import java.net.Socket                          // a plain TCP socket

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
class StreamClient(                             // constructor parameters become the object's private fields:
    private val host: String,                   // where to connect (127.0.0.1)
    private val port: Int,                       // which port (5000)
    private val onNal: (ByteArray) -> Unit,      // callback: called once per complete NAL unit (wired to H264Decoder.submitNal)
    private val onStatus: (String) -> Unit       // callback: report connection status to the UI
) {
    companion object {                           // shared constants ("static")
        private const val TAG = "StreamClient"   // logcat tag
        private const val READ_CHUNK = 32 * 1024 // read up to 32 KB per socket read
        private const val INITIAL_BUFFER = 256 * 1024   // start the accumulation buffer at 256 KB (grows if needed)
        private const val RETRY_MS = 1000L       // wait 1 second between reconnect attempts
    }

    @Volatile private var running = false        // @Volatile = safe to read/write across threads; the loop's on/off switch
    private var thread: Thread? = null           // the background network thread
    private var socket: Socket? = null           // the current TCP socket (so stop() can force-close it)

    fun start() {                                // called from MainActivity.surfaceCreated
        running = true                           // arm the loop
        thread = Thread({ connectLoop() }, "stream-client").apply { start() }   // spawn a named background thread running connectLoop() and start it
    }

    private fun connectLoop() {                  // runs on the background thread: connect, read, and reconnect forever while running
        while (running) {                        // keep trying as long as we're active (this is the auto-reconnect loop)
            try {
                onStatus("Connecting to $host:$port…")   // tell the UI we're attempting to connect
                val s = Socket()                 // create an unconnected socket so we can set options before connecting
                s.tcpNoDelay = true              // disable Nagle's algorithm -> send small packets immediately -> lower latency
                s.connect(InetSocketAddress(host, port), 3000)   // connect with a 3-second timeout
                socket = s                       // store it so stop() can close it
                onStatus("Connected")            // update the UI
                Log.i(TAG, "connected to $host:$port")
                readStream(s.getInputStream())   // BLOCK here reading bytes until the connection drops or stop() is called
            } catch (e: Exception) {             // connect failed, or the read loop threw (connection lost)
                if (running) {                   // only treat it as an error/retry if we WEREN'T deliberately stopped
                    Log.w(TAG, "connection lost: ${e.message}; retrying")
                    onStatus("Reconnecting…")
                    Thread.sleep(RETRY_MS)       // back off 1s before trying again
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}   // always close the socket (ignore errors)
                socket = null                    // clear the reference
            }
        }
    }

    /** Read bytes, extract complete NAL units, emit each via [onNal]. */
    private fun readStream(input: InputStream) {
        var buffer = ByteArray(INITIAL_BUFFER)   // the accumulation buffer where partial data piles up between reads
        var len = 0                              // how many bytes in `buffer` are currently valid
        val chunk = ByteArray(READ_CHUNK)        // scratch buffer for each individual socket read

        while (running) {                        // loop until stopped or the stream ends (throws)
            val n = input.read(chunk)            // read up to READ_CHUNK bytes; BLOCKS until some arrive; returns the count
            if (n < 0) throw RuntimeException("stream ended")   // -1 = server closed the connection -> bubble up to reconnect
            if (n == 0) continue                 // 0 bytes (rare) -> just try again

            // Grow the buffer if needed, then append the new bytes.
            if (len + n > buffer.size) {         // not enough room for the new bytes?
                buffer = buffer.copyOf(maxOf(buffer.size * 2, len + n))   // grow to at least double, or exactly what's needed if that's bigger
            }
            System.arraycopy(chunk, 0, buffer, len, n)   // copy the freshly-read `n` bytes onto the end of `buffer`
            len += n                             // the valid length grew by n

            // Find the first start code; anything before it is junk we discard.
            var nalStart = findStartCode(buffer, 0, len)   // locate the first Annex-B start code (00 00 01) = start of the current NAL
            if (nalStart < 0) {                  // no start code anywhere yet (only happens before the very first one)
                // No start code yet; if junk is piling up, drop it but keep a small
                // tail in case a start code straddles the boundary.
                if (len > READ_CHUNK) {          // a lot of junk accumulated...
                    val keep = 3                 // ...keep the last 3 bytes in case they're the first 2/3 of a start code split across reads
                    System.arraycopy(buffer, len - keep, buffer, 0, keep)   // move those 3 bytes to the front
                    len = keep                   // and discard everything else
                }
                continue                         // wait for more bytes
            }

            // Emit every NAL for which we can see the NEXT start code.
            while (true) {
                val nextStart = findStartCode(buffer, nalStart + 3, len)   // search for the NEXT start code, after the current one
                if (nextStart < 0) break         // can't see the next start code yet -> the current NAL isn't complete -> stop emitting
                val nal = buffer.copyOfRange(nalStart, nextStart)   // a complete NAL is the bytes from this start code up to (not incl.) the next
                onNal(nal)                       // hand the complete NAL to the decoder
                nalStart = nextStart             // advance: the next NAL begins at the start code we just found
            }

            // Compact: move the unconsumed remainder (the in-progress NAL) to front.
            if (nalStart > 0) {                  // if we emitted at least one NAL...
                System.arraycopy(buffer, nalStart, buffer, 0, len - nalStart)   // shift the leftover (the incomplete NAL) back to offset 0
                len -= nalStart                  // ...and adjust the valid length. (Keeping from nalStart is what makes straddled start codes safe.)
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
        var i = maxOf(from, 0)                   // start scanning at `from` (but never below 0)
        while (i + 2 < limit) {                  // need 3 bytes available (i, i+1, i+2) within the valid region
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {   // is this the 3-byte pattern 00 00 01?
                return i                         // yes -> return the index where the start code begins
            }
            i++                                  // no -> slide one byte forward and check again
        }
        return -1                                // scanned everything, no start code found
    }

    fun stop() {                                 // called from MainActivity to shut the client down
        running = false                          // flip the switch so the loops exit
        try { socket?.close() } catch (_: Exception) {}   // force-close the socket to unblock input.read() immediately
        thread?.interrupt()                      // interrupt the thread in case it's sleeping/blocked
        thread = null                            // drop the reference
    }
}
