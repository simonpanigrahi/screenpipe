package com.example.usbdisplay                 // the app's package; must match the directory path and the namespace in build.gradle.kts

import android.os.Bundle                          // Bundle = the saved-state object Android hands to onCreate
import android.view.SurfaceHolder                 // SurfaceHolder = the controller/callback interface for a SurfaceView's drawing surface
import android.view.SurfaceView                   // SurfaceView = a view with its own dedicated drawing surface (what MediaCodec renders onto)
import android.view.WindowManager                 // WindowManager = window-level flags, e.g. keep-screen-on
import android.widget.TextView                    // TextView = the on-screen "Waiting for stream…" status label
import androidx.appcompat.app.AppCompatActivity   // AppCompatActivity = the base Activity class with backward-compatible features
import androidx.core.view.WindowCompat            // helpers for edge-to-edge / fullscreen across Android versions
import androidx.core.view.WindowInsetsCompat      // describes the system bars (status/navigation) we want to hide
import androidx.core.view.WindowInsetsControllerCompat   // the object that actually hides/shows those system bars

/**
 * Entry point. Shows a fullscreen SurfaceView and, once the surface is ready,
 * starts the decoder + network client. Connects to 127.0.0.1 because the
 * adb-reverse tunnel maps the tablet's localhost to the laptop's localhost over
 * the USB cable.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {   // our screen; also implements SurfaceHolder.Callback so it's notified when the surface is created/destroyed

    companion object {                            // companion object = the Kotlin equivalent of "static" members shared by the class
        // 127.0.0.1 (NOT the laptop's LAN IP): the reverse tunnel forwards this
        // over USB to the laptop's GStreamer server.
        private const val HOST = "127.0.0.1"      // connect to localhost; adb reverse tunnels it over USB to the daemon
        private const val PORT = 5000             // must match PORT in gst_common.py
    }

    private lateinit var surfaceView: SurfaceView // the fullscreen surface; "lateinit" = assigned later in onCreate, not at construction
    private lateinit var status: TextView         // the status overlay label

    private var decoder: H264Decoder? = null      // our MediaCodec wrapper; null until the surface exists
    private var client: StreamClient? = null      // our TCP reader; null until the surface exists

    override fun onCreate(savedInstanceState: Bundle?) {   // called once when the Activity is first created
        super.onCreate(savedInstanceState)        // always call the superclass implementation first
        setContentView(R.layout.activity_main)    // inflate res/layout/activity_main.xml as this screen's UI

        surfaceView = findViewById(R.id.surface)  // grab the SurfaceView defined in the layout (android:id="@+id/surface")
        status = findViewById(R.id.status)        // grab the status TextView (android:id="@+id/status")

        // Keep the screen awake while acting as a display.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // prevent the tablet from sleeping while it's being used as a monitor

        enableImmersiveFullscreen()               // hide the status/navigation bars for a true fullscreen image

        // We can only touch the Surface after it's created.
        surfaceView.holder.addCallback(this)      // register THIS activity to be told when the surface is created/changed/destroyed (-> surfaceCreated below)
    }

    private fun enableImmersiveFullscreen() {     // hides the system bars so the video fills the entire screen
        WindowCompat.setDecorFitsSystemWindows(window, false)   // let our content draw behind/under the system bars (edge-to-edge)
        WindowInsetsControllerCompat(window, surfaceView).apply {   // get the controller for this window's insets and configure it...
            hide(WindowInsetsCompat.Type.systemBars())          // ...hide both the status bar and the navigation bar
            systemBarsBehavior =                                // ...and define how they reappear:
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE   // only briefly, on a swipe, then auto-hide again
        }
    }

    private fun setStatus(text: String) {         // helper to update the status label from ANY thread
        runOnUiThread {                           // UI can only be touched on the main thread; this hops us there
            status.text = text                    // set the label text
            status.visibility = if (text.isEmpty()) TextView.GONE else TextView.VISIBLE   // empty text -> hide the label entirely (frames are flowing)
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {   // called by Android when the drawing surface becomes available
        val dec = H264Decoder(holder.surface) { setStatus(it) }   // create the decoder targeting this surface; the trailing lambda is its status callback (it = the status string)
        dec.start()                               // start the MediaCodec decoder + its feeder thread
        decoder = dec                             // remember it so we can stop it later

        val cl = StreamClient(HOST, PORT, onNal = dec::submitNal) { setStatus(it) }   // create the TCP client; each NAL it parses is handed to decoder.submitNal; trailing lambda = status callback
        cl.start()                                // start the network thread (it connects and begins reading)
        client = cl                               // remember it so we can stop it later
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {   // called if the surface's size/format changes
        // No-op for Phase 1. Aspect-fit handling lives here in Milestone 2.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {   // called when the surface goes away (app backgrounded, screen off, etc.)
        client?.stop(); client = null             // stop the network client first (so no new NALs arrive)...
        decoder?.stop(); decoder = null           // ...then stop the decoder (releasing the now-invalid surface)
    }

    override fun onDestroy() {                     // called when the Activity is being destroyed for good
        super.onDestroy()                          // call the superclass implementation
        client?.stop()                             // belt-and-suspenders cleanup in case surfaceDestroyed didn't fire
        decoder?.stop()
    }
}
