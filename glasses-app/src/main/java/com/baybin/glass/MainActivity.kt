package com.baybin.glass

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import kotlin.random.Random

/**
 * The whole glasses UI: aim at the live picture, right key captures, phone names the
 * item and the bin, left key leaves.
 *
 * The glasses do not know the city. The phone already has it and sends back two short
 * lines (bin word, item name).
 *
 * One scan at a time. Each scan has an id; a result for any other id is stale and
 * dropped. If the phone does not answer within [RESULT_TIMEOUT_MS], or the link
 * drops while waiting, the lens says it is not connected.
 *
 * Every finished scan logs one `BB_SCAN ...` line; tools/probe.py parses those.
 */
class MainActivity : Activity(), PhoneLink.Listener {

    private class Scan(val id: Int, val captureOnly: Boolean, val save: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        var openMs = -1L
        var shotMs = -1L
        var sendMs = -1L
        var bytes = -1
        /** The photo has been handed to the link (the write itself is asynchronous). */
        var sending = false
    }

    private class Probe(val id: Int, val size: Int) {
        val t0 = SystemClock.elapsedRealtime()
        var callMs = -1L
        var rc = 0
    }

    private lateinit var status: TextView
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var hint: TextView
    private lateinit var freeze: ImageView
    private lateinit var camera: OneShotCamera
    /** The captured still is on screen, so the next right key goes back to aiming. */
    private var viewing = false
    private var stillGen = 0
    private lateinit var link: PhoneLink
    private val main = Handler(Looper.getMainLooper())
    private var nextId = Random.nextInt(1, 1 shl 20)
    private var scan: Scan? = null
    private var probe: Probe? = null
    private var debugReceiver: BroadcastReceiver? = null

    private val scanTimeout = Runnable {
        scan?.let { finishScan(it, "timeout", "没连上", "手机没回应") }
    }
    private val backToLive = Runnable { showLive() }
    private val probeTimeout = Runnable {
        probe?.let { finishProbe(it, "timeout", -1) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.status)
        line1 = findViewById(R.id.line1)
        line2 = findViewById(R.id.line2)
        hint = findViewById(R.id.hint)
        freeze = findViewById(R.id.freeze)

        camera = OneShotCamera(this, findViewById(R.id.preview))
        link = PhoneLink.get(this)
        link.listener = this
        renderStatus()
        show("对准", "")
        hint.text = HINT_SHOOT

        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        if (hasBluetooth()) link.start()
        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (hasCamera()) camera.open()
    }

    override fun onPause() {
        camera.close()
        super.onPause()
    }

    override fun onDestroy() {
        link.listener = null
        main.removeCallbacksAndMessages(null)
        debugReceiver?.let { unregisterReceiver(it) }
        camera.release()
        super.onDestroy()
    }

    // ---- input ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isScanKey(keyCode) || isExitKey(keyCode)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Log.i(TAG, "key $keyCode")
        if (isExitKey(keyCode)) {
            finish()
            return true
        }
        if (isScanKey(keyCode)) {
            onScanKey()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // Temple tap and the ring's right (confirm) key arrive as DPAD_CENTER/ENTER.
    // Ring left is the photo key (CAMERA). BACK is not consumed: a right double-click still leaves.
    private fun isScanKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_SPACE ||
        k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        k == KeyEvent.KEYCODE_HEADSETHOOK

    private fun isExitKey(k: Int) = k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_FOCUS

    /** Right key while a still is up returns to the live picture. The next one shoots. */
    private fun onScanKey() {
        if (scan != null || probe != null) {
            Log.i(TAG, "busy, tap ignored")
            return
        }
        if (viewing) {
            showLive()
            return
        }
        startScan(captureOnly = false, save = false)
    }

    // ---- scan ----

    private fun startScan(captureOnly: Boolean, save: Boolean) {
        if (scan != null || probe != null) {
            Log.i(TAG, "busy, tap ignored")
            return
        }
        main.removeCallbacks(backToLive)
        if (!hasCamera()) {
            show("相机没开", "允许相机后再按右键")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERMS)
            return
        }
        val s = Scan(nextId++, captureOnly, save)
        if (!captureOnly && !link.linkUp) {
            // Fail fast: without the phone there is nothing to send the photo to.
            scan = s
            finishScan(s, "no_link", "没连上", "先打开手机上的 BayBin")
            return
        }
        scan = s
        show("在看…", "")
        hint.text = HINT_SHOOT
        if (!captureOnly) link.sendPrepare()
        camera.capture { shot, error -> onShot(s, shot, error) }
    }

    private fun onShot(s: Scan, shot: OneShotCamera.Shot?, error: String?) {
        if (scan !== s) return
        if (error == "paused") {
            scan = null
            main.removeCallbacks(scanTimeout)
            return
        }
        if (shot == null) {
            finishScan(s, "camera_error", "相机出错", error ?: "")
            return
        }
        s.openMs = shot.openMs
        s.shotMs = shot.totalMs
        s.bytes = shot.jpeg.size
        if (BuildConfig.DEBUG && s.save) saveForDebug(shot.jpeg)
        showStill(s, shot)

        if (s.captureOnly) {
            finishScan(s, "capture_only", "拍到了", "${shot.jpeg.size / 1024} KB，${shot.totalMs} ms")
            return
        }
        if (!link.linkUp) {
            finishScan(s, "no_link", "没连上", "先打开手机上的 BayBin")
            return
        }
        show("在看…", "")
        s.sending = true
        // The writer thread holds the JPEG until it is on the socket; nothing is kept after that.
        link.sendScan(s.id, shot.jpeg, shot.rotation) { ok, ms ->
            s.sendMs = ms
            if (!ok) finishScan(s, "send_failed", "没连上", "手机没收到")
        }
        main.postDelayed(scanTimeout, RESULT_TIMEOUT_MS)
    }

    private fun finishScan(s: Scan, outcome: String, text1: String, text2: String) {
        if (scan !== s) return
        scan = null
        main.removeCallbacks(scanTimeout)
        show(text1, text2)
        hint.text = if (viewing) HINT_AIM else HINT_SHOOT
        if (viewing) main.postDelayed(backToLive, RESULT_HOLD_MS)
        Log.i(TAG, "BB_SCAN id=${s.id} outcome=$outcome open=${s.openMs} shot=${s.shotMs} " +
            "send=${s.sendMs} bytes=${s.bytes} total=${SystemClock.elapsedRealtime() - s.t0}")
    }

    // ---- transfer probe (debug builds only, driven by tools/probe.py) ----

    private fun startProbe(kb: Int) {
        if (scan != null || probe != null) {
            Log.i(TAG, "BB_PROBE busy")
            return
        }
        if (!link.linkUp) {
            Log.i(TAG, "BB_PROBE id=0 size=${kb * 1024} raw=false rc=-1 call=-1 outcome=no_link received=-1 rtt=0")
            return
        }
        val p = Probe(nextId++, kb * 1024)
        probe = p
        link.sendProbe(p.id, p.size) { ok, ms ->
            p.callMs = ms
            if (!ok) {
                p.rc = -1
                finishProbe(p, "send_failed", -1)
            }
        }
        show("Probe", "${kb} KB sent, waiting…")
        main.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
    }

    private fun finishProbe(p: Probe, outcome: String, received: Int) {
        if (probe !== p) return
        probe = null
        main.removeCallbacks(probeTimeout)
        val rtt = SystemClock.elapsedRealtime() - p.t0
        show("Probe ${p.size / 1024} KB", "$outcome, rtt $rtt ms")
        Log.i(TAG, "BB_PROBE id=${p.id} size=${p.size} raw=false rc=${p.rc} call=${p.callMs} " +
            "outcome=$outcome received=$received rtt=$rtt")
    }

    // ---- PhoneLink.Listener ----

    override fun onLinkChanged(up: Boolean) {
        renderStatus()
        if (!up) {
            scan?.let { if (it.sending) finishScan(it, "link_lost", "没连上", "手机断了") }
            probe?.let { finishProbe(it, "link_lost", -1) }
        }
    }

    override fun onPhoneAppSeen(version: String) = renderStatus()

    override fun onResult(reqId: Int, kind: Int, line1: String, line2: String) {
        val s = scan
        if (s == null || s.id != reqId) {
            Log.i(TAG, "stale result id=$reqId")
            return
        }
        finishScan(s, "result_$kind", line1, line2)
    }

    override fun onProbeAck(reqId: Int, receivedBytes: Int) {
        val p = probe ?: return
        if (p.id != reqId) return
        finishProbe(p, if (receivedBytes == p.size) "ok" else "short", receivedBytes)
    }

    // ---- view ----

    private fun show(text1: String, text2: String) {
        line1.text = text1
        line2.text = text2
    }

    /** Hold the JPEG so the user can see what was sent, then return to the live picture. */
    private fun showStill(s: Scan, shot: OneShotCamera.Shot) {
        val gen = ++stillGen
        camera.frame(shot.jpeg, shot.rotation) { bmp ->
            if (gen != stillGen || isDestroyed) {
                bmp?.recycle()
                return@frame
            }
            if (bmp == null) return@frame
            val old = freeze.drawable
            freeze.setImageBitmap(bmp)
            freeze.visibility = View.VISIBLE
            viewing = true
            if (old is BitmapDrawable && old.bitmap !== bmp) old.bitmap?.recycle()
            // Result can beat the decode. Start the hold from whichever finishes last.
            if (scan !== s) {
                hint.text = HINT_AIM
                main.removeCallbacks(backToLive)
                main.postDelayed(backToLive, RESULT_HOLD_MS)
            }
        }
    }

    private fun showLive() {
        stillGen++
        main.removeCallbacks(backToLive)
        viewing = false
        val old = freeze.drawable
        freeze.setImageDrawable(null)
        freeze.visibility = View.GONE
        if (old is BitmapDrawable) old.bitmap?.recycle()
        hint.text = HINT_SHOOT
    }

    private fun renderStatus() {
        status.text = when {
            !link.linkUp -> "没连上"
            link.phoneAppVersion == null -> "连上了…"
            else -> "已连上"
        }
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetooth() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBluetooth()) link.start()
        if (hasCamera()) camera.open()
    }

    // ---- debug hooks: adb shell am broadcast -a com.baybin.glass.SCAN -p com.baybin.glass ----

    private fun registerDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SCAN -> startScan(
                        captureOnly = intent.getBooleanExtra("captureOnly", false),
                        save = intent.getBooleanExtra("save", false),
                    )
                    ACTION_PROBE -> startProbe(intent.getIntExtra("kb", 64))
                    ACTION_PING -> Log.i(TAG, "BB_PING link=${link.linkUp} phone=${link.phoneAppVersion}")
                    // Layout check for long rule texts: shows the two lines as a result would.
                    ACTION_SHOW -> show(intent.getStringExtra("l1").orEmpty(), intent.getStringExtra("l2").orEmpty())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SCAN)
            addAction(ACTION_PROBE)
            addAction(ACTION_PING)
            addAction(ACTION_SHOW)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, filter)
        debugReceiver = r
    }

    private fun saveForDebug(jpeg: ByteArray) {
        val f = File(getExternalFilesDir(null), "last.jpg")
        f.writeBytes(jpeg)
        Log.i(TAG, "saved ${f.absolutePath}")
    }

    companion object {
        private const val TAG = "BayBin"
        private const val REQ_PERMS = 1
        private const val RESULT_TIMEOUT_MS = 8000L
        private const val RESULT_HOLD_MS = 1200L
        private const val PROBE_TIMEOUT_MS = 20000L
        private const val HINT_SHOOT = "右键拍 · 左键退出"
        private const val HINT_AIM = "右键再瞄准 · 左键退出"
        private const val ACTION_SCAN = "com.baybin.glass.SCAN"
        private const val ACTION_PROBE = "com.baybin.glass.PROBE"
        private const val ACTION_PING = "com.baybin.glass.PING"
        private const val ACTION_SHOW = "com.baybin.glass.SHOW"
    }
}
