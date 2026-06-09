package com.example.usbdisplay

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Entry point. Shows a fullscreen SurfaceView and, once the surface is ready,
 * starts the decoder + network client. Connects to 127.0.0.1 because the
 * adb-reverse tunnel maps the tablet's localhost to the laptop's localhost over
 * the USB cable.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        // 127.0.0.1 (NOT the laptop's LAN IP): the reverse tunnel forwards this
        // over USB to the laptop's GStreamer server.
        private const val HOST = "127.0.0.1"
        private const val PORT = 5000
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView

    private var decoder: H264Decoder? = null
    private var client: StreamClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface)
        status = findViewById(R.id.status)

        // Keep the screen awake while acting as a display.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableImmersiveFullscreen()

        // We can only touch the Surface after it's created.
        surfaceView.holder.addCallback(this)
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, surfaceView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            status.text = text
            status.visibility = if (text.isEmpty()) TextView.GONE else TextView.VISIBLE
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        val dec = H264Decoder(holder.surface) { setStatus(it) }
        dec.start()
        decoder = dec

        val cl = StreamClient(HOST, PORT, onNal = dec::submitNal) { setStatus(it) }
        cl.start()
        client = cl
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // No-op for Phase 1. Aspect-fit handling lives here in Milestone 2.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        client?.stop(); client = null
        decoder?.stop(); decoder = null
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.stop()
        decoder?.stop()
    }
}
