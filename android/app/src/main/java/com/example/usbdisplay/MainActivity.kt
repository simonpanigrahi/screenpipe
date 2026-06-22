package com.example.usbdisplay                 // the app's package; must match the directory path and the namespace in build.gradle.kts

import android.content.Context                    // Context = used to look up the SharedPreferences store
import android.os.Bundle                          // Bundle = the saved-state object Android hands to onCreate
import android.os.Handler                         // Handler = posts delayed work (used to auto-hide the control bar)
import android.os.Looper                          // Looper = the main thread's message loop the Handler attaches to
import android.view.SurfaceHolder                 // SurfaceHolder = the controller/callback interface for a SurfaceView's drawing surface
import android.view.SurfaceView                   // SurfaceView = a view with its own dedicated drawing surface (what MediaCodec renders onto)
import android.view.View                          // View = base class; used for visibility constants and the tap listener
import android.view.WindowManager                 // WindowManager = window-level flags, e.g. keep-screen-on
import android.widget.Button                      // Button = the tappable control-bar toggles
import android.widget.FrameLayout                 // FrameLayout = the root container we resize the surface within / listen for taps on
import android.widget.TextView                    // TextView = the status label and the stats overlay
import androidx.appcompat.app.AppCompatActivity   // AppCompatActivity = the base Activity class with backward-compatible features
import androidx.core.view.WindowCompat            // helpers for edge-to-edge / fullscreen across Android versions
import androidx.core.view.WindowInsetsCompat      // describes the system bars (status/navigation) we want to hide
import androidx.core.view.WindowInsetsControllerCompat   // the object that actually hides/shows those system bars

/**
 * Entry point. Shows a fullscreen SurfaceView fed by the decoder + network client,
 * plus a lightweight, tap-to-reveal control bar with a few useful toggles:
 *
 *   • Connect / Disconnect — start or stop the stream by hand.
 *   • Fit / Stretch        — aspect-fit (letterbox) the video, or stretch to fill.
 *   • Stats                — a small overlay showing resolution + connection state.
 *   • Awake                — keep the screen on while acting as a display.
 *
 * Toggle states are remembered across launches via SharedPreferences. The bar is
 * hidden by default (this is a display, not an app) and auto-hides after a few
 * seconds; tap anywhere to bring it back.
 *
 * Connects to 127.0.0.1 because the adb-reverse tunnel maps the tablet's localhost
 * to the laptop's localhost over the USB cable.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {   // our screen; also implements SurfaceHolder.Callback so it's notified when the surface is created/destroyed

    companion object {                            // companion object = the Kotlin equivalent of "static" members shared by the class
        // 127.0.0.1 (NOT the laptop's LAN IP): the reverse tunnel forwards this
        // over USB to the laptop's GStreamer server.
        private const val HOST = "127.0.0.1"      // connect to localhost; adb reverse tunnels it over USB to the daemon
        private const val PORT = 5000             // must match PORT in gst_common.py

        private const val PREFS = "ui"            // SharedPreferences file name for the remembered toggle states
        private const val KEY_FIT = "aspectFit"   // pref key: aspect-fit (true) vs stretch (false)
        private const val KEY_STATS = "statsOn"   // pref key: show the stats overlay
        private const val KEY_AWAKE = "keepAwake" // pref key: keep the screen on

        private const val CONTROLS_HIDE_MS = 4000L // auto-hide the control bar this long after the last interaction
    }

    private lateinit var root: FrameLayout        // the root container (we resize the surface within it and listen for taps on it)
    private lateinit var surfaceView: SurfaceView // the video surface; resized for aspect-fit
    private lateinit var status: TextView         // the big centered status label
    private lateinit var stats: TextView          // the small top-left diagnostics overlay
    private lateinit var controls: View           // the bottom control bar (shown on tap)
    private lateinit var btnConnect: Button       // Connect/Disconnect toggle
    private lateinit var btnFit: Button           // Fit/Stretch toggle
    private lateinit var btnStats: Button         // Stats on/off toggle
    private lateinit var btnAwake: Button         // Keep-awake on/off toggle

    private var decoder: H264Decoder? = null      // our MediaCodec wrapper; null when not streaming
    private var client: StreamClient? = null      // our TCP reader; null when not streaming

    // ---- toggle / display state ----
    private var wantConnected = true              // user intent: should we be streaming? (resets to true each launch)
    private var aspectFit = true                  // true = letterbox to preserve aspect; false = stretch to fill
    private var statsOn = false                   // is the stats overlay visible?
    private var keepAwake = true                  // keep the screen on while displaying?
    private var videoW = 0                        // last known decoded width  (0 = unknown yet)
    private var videoH = 0                        // last known decoded height (0 = unknown yet)
    private var connState = "Idle"                // human-readable connection state, shown in stats

    private val handler = Handler(Looper.getMainLooper())   // posts the delayed "hide the control bar" job onto the main thread
    private val hideControls = Runnable { setControlsVisible(false) }   // the actual hide action

    override fun onCreate(savedInstanceState: Bundle?) {   // called once when the Activity is first created
        super.onCreate(savedInstanceState)        // always call the superclass implementation first
        setContentView(R.layout.activity_main)    // inflate res/layout/activity_main.xml as this screen's UI

        root = findViewById(R.id.root)            // the FrameLayout root
        surfaceView = findViewById(R.id.surface)  // the SurfaceView the decoder renders onto
        status = findViewById(R.id.status)        // the centered status label
        stats = findViewById(R.id.stats)          // the stats overlay
        controls = findViewById(R.id.controls)    // the control bar
        btnConnect = findViewById(R.id.btnConnect)
        btnFit = findViewById(R.id.btnFit)
        btnStats = findViewById(R.id.btnStats)
        btnAwake = findViewById(R.id.btnAwake)

        // Restore remembered toggle preferences (connection intent is always "on" at launch).
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
            aspectFit = p.getBoolean(KEY_FIT, true)     // default: aspect-fit on
            statsOn = p.getBoolean(KEY_STATS, false)    // default: stats off
            keepAwake = p.getBoolean(KEY_AWAKE, true)   // default: keep awake on
        }

        enableImmersiveFullscreen()               // hide the status/navigation bars for a true fullscreen image

        // Wire up the toggle buttons.
        btnConnect.setOnClickListener { toggleConnection() }
        btnFit.setOnClickListener { aspectFit = !aspectFit; persistPrefs(); applyAspectFit(); refreshButtons(); keepControlsAlive() }
        btnStats.setOnClickListener { statsOn = !statsOn; persistPrefs(); applyStats(); refreshButtons(); keepControlsAlive() }
        btnAwake.setOnClickListener { keepAwake = !keepAwake; persistPrefs(); applyKeepAwake(); refreshButtons(); keepControlsAlive() }

        // Tap anywhere (outside the buttons) to reveal/hide the control bar.
        root.setOnClickListener { setControlsVisible(controls.visibility != View.VISIBLE) }

        // Apply the restored toggle states to the UI.
        applyKeepAwake()                          // add/clear FLAG_KEEP_SCREEN_ON
        applyStats()                              // show/hide the stats overlay
        refreshButtons()                          // label every button with its current state

        // Re-fit the surface whenever the container is (re)laid out, e.g. on rotation.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspectFit() }

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

    // ---- streaming control ----

    /** Start the decoder + network client (no-op if the surface isn't ready or we're already running). */
    private fun startStream() {
        val holder = surfaceView.holder           // the surface we render onto
        if (decoder != null || !holder.surface.isValid) return   // already streaming, or no valid surface yet -> nothing to do

        val dec = H264Decoder(holder.surface, { setStatus(it) }) { w, h ->   // create the decoder; trailing lambda = resolution callback
            runOnUiThread {                        // codec callbacks arrive on a codec thread; hop to the UI thread to touch views
                videoW = w; videoH = h             // remember the real decoded dimensions
                applyAspectFit()                   // resize the surface to match (if Fit is on)
                applyStats()                       // refresh the overlay text
            }
        }
        dec.start()                                // start the MediaCodec decoder + its feeder thread
        decoder = dec                              // remember it so we can stop it later

        val cl = StreamClient(HOST, PORT, onNal = dec::submitNal) { s ->   // create the TCP client; each NAL it parses is handed to decoder.submitNal
            runOnUiThread { connState = s; applyStats() }   // remember the connection state for the stats overlay
            setStatus(s)                           // and surface it in the big centered label
        }
        cl.start()                                 // start the network thread (it connects and begins reading)
        client = cl                                // remember it so we can stop it later
    }

    /** Stop the decoder + network client (safe to call when not streaming). */
    private fun stopStream() {
        client?.stop(); client = null             // stop the network client first (so no new NALs arrive)...
        decoder?.stop(); decoder = null           // ...then stop the decoder (releasing the now-invalid surface)
    }

    /** Connect/Disconnect button: flip the user intent and start/stop accordingly. */
    private fun toggleConnection() {
        wantConnected = !wantConnected            // flip the intent
        if (wantConnected) {                      // user wants to connect...
            connState = "Connecting…"
            startStream()                         // ...start if the surface is available (it is, since the bar is on screen)
        } else {                                  // user wants to disconnect...
            stopStream()                          // ...tear the stream down
            connState = "Disconnected"
            setStatus("Disconnected — tap to show controls")   // tell the user how to get back
        }
        refreshButtons()                          // update the button label (Connect <-> Disconnect)
        applyStats()                              // refresh the overlay
        keepControlsAlive()                       // keep the bar visible a bit longer after the tap
    }

    // ---- toggle appliers ----

    /** Resize the SurfaceView to aspect-fit the video, or stretch it to fill. */
    private fun applyAspectFit() {
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams   // the surface's position/size within the root FrameLayout

        // Compute the DESIRED size first, without touching layoutParams yet.
        val newW: Int
        val newH: Int
        if (aspectFit && videoW > 0 && videoH > 0 && root.width > 0 && root.height > 0) {
            // Scale the video as large as possible while fitting inside the container.
            val scale = minOf(root.width.toFloat() / videoW, root.height.toFloat() / videoH)
            newW = (videoW * scale).toInt()    // letterboxed width
            newH = (videoH * scale).toInt()   // letterboxed height
        } else {
            // Stretch (or unknown size): fill the whole container.
            newW = FrameLayout.LayoutParams.MATCH_PARENT
            newH = FrameLayout.LayoutParams.MATCH_PARENT
        }

        // Only reassign (which calls requestLayout) when something ACTUALLY changed.
        // applyAspectFit() runs from an OnLayoutChangeListener; unconditionally setting
        // layoutParams here would requestLayout during layout -> an infinite layout loop
        // that thrashes the Surface and crashes MediaCodec. This guard breaks that loop.
        if (lp.width == newW && lp.height == newH && lp.gravity == android.view.Gravity.CENTER) return

        lp.width = newW
        lp.height = newH
        lp.gravity = android.view.Gravity.CENTER   // keep the (possibly smaller) surface centered, with black bars around it
        surfaceView.layoutParams = lp              // apply -> triggers a relayout
    }

    /** Show/hide the stats overlay and refresh its text. */
    private fun applyStats() {
        stats.visibility = if (statsOn) View.VISIBLE else View.GONE
        if (statsOn) {
            val res = if (videoW > 0) "${videoW}x$videoH" else "—"   // resolution, or a dash before the first frame
            stats.text = "state: $connState\nres:   $res\nfit:   ${if (aspectFit) "on" else "stretch"}"
        }
    }

    /** Add or clear the keep-screen-on window flag. */
    private fun applyKeepAwake() {
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- control bar visibility ----

    /** Show or hide the control bar; showing it schedules an auto-hide. */
    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)      // cancel any pending auto-hide
        if (visible) handler.postDelayed(hideControls, CONTROLS_HIDE_MS)   // re-arm the auto-hide
    }

    /** Reset the auto-hide timer so the bar stays up while the user is interacting. */
    private fun keepControlsAlive() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    /** Re-label every button to reflect the current state. */
    private fun refreshButtons() {
        btnConnect.text = if (wantConnected) "Disconnect" else "Connect"
        btnFit.text = if (aspectFit) "Fit: On" else "Fit: Off"
        btnStats.text = if (statsOn) "Stats: On" else "Stats: Off"
        btnAwake.text = if (keepAwake) "Awake: On" else "Awake: Off"
    }

    private fun persistPrefs() {                  // save the toggle states so they survive a relaunch
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FIT, aspectFit)
            .putBoolean(KEY_STATS, statsOn)
            .putBoolean(KEY_AWAKE, keepAwake)
            .apply()
    }

    private fun setStatus(text: String) {         // helper to update the status label from ANY thread
        runOnUiThread {                           // UI can only be touched on the main thread; this hops us there
            status.text = text                    // set the label text
            status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE   // empty text -> hide the label entirely (frames are flowing)
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {   // called by Android when the drawing surface becomes available
        if (wantConnected) startStream()          // honour the user's intent: start streaming if they want to be connected
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {   // called if the surface's size/format changes
        decoder?.setSurface(holder.surface)       // a resize hands us a new Surface; retarget the running codec so it doesn't fault
        applyAspectFit()                          // re-fit in case the surface dimensions changed
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {   // called when the surface goes away (app backgrounded, screen off, etc.)
        stopStream()                              // tear down the stream; wantConnected is preserved so we resume on surfaceCreated
    }

    override fun onDestroy() {                     // called when the Activity is being destroyed for good
        super.onDestroy()                          // call the superclass implementation
        handler.removeCallbacks(hideControls)      // drop any pending UI callback
        stopStream()                               // belt-and-suspenders cleanup in case surfaceDestroyed didn't fire
    }
}
