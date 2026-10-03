package com.baybin.glass

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.baybin.protocol.Proto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The glasses end of the link: an insecure RFCOMM server the phone app connects to
 * (frame layout in [Proto]).
 *
 * One per process. An accept thread waits for the phone; each connection gets a reader
 * thread; everything we send goes through one writer thread, so the main thread never
 * blocks on Bluetooth. A new connection replaces the old one (the phone reconnected).
 * Callbacks reach [listener] on the main thread.
 */
class PhoneLink private constructor(context: Context) {

    interface Listener {
        fun onLinkChanged(up: Boolean)
        fun onPhoneAppSeen(version: String)
        fun onResult(reqId: Int, kind: Int, line1: String, line2: String)
        fun onProbeAck(reqId: Int, receivedBytes: Int)
    }

    var listener: Listener? = null

    /** The phone app is connected. Main thread. */
    var linkUp = false
        private set

    /** Version the phone app sent in its HELLO. Main thread. */
    var phoneAppVersion: String? = null
        private set

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "baybin-send") }
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    @Volatile private var accepting = false

    /** Starts listening. Needs BLUETOOTH_CONNECT on Android 12+; call again once it is granted. */
    fun start() {
        if (accepting) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.w(TAG, "no Bluetooth adapter")
            return
        }
        accepting = true
        Thread({ acceptLoop(adapter) }, "baybin-accept").apply { isDaemon = true }.start()
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(adapter: BluetoothAdapter) {
        var server: BluetoothServerSocket? = null
        while (true) {
            try {
                if (!adapter.isEnabled) {
                    SystemClock.sleep(3000)
                    continue
                }
                val ss = server ?: adapter.listenUsingInsecureRfcommWithServiceRecord(
                    Proto.SERVICE_NAME, Proto.SERVICE_UUID).also { server = it }
                Log.i(TAG, "listening for the phone app")
                attach(ss.accept())
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth permission missing; link not started")
                accepting = false
                return
            } catch (e: IOException) {
                Log.w(TAG, "accept: ${e.message}")
                try { server?.close() } catch (_: IOException) {}
                server = null
                SystemClock.sleep(2000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attach(s: BluetoothSocket) {
        Log.i(TAG, "link up: ${s.remoteDevice?.address}")
        val o = DataOutputStream(BufferedOutputStream(s.outputStream, 16 * 1024))
        val old = synchronized(lock) {
            val prev = socket
            socket = s
            out = o
            prev
        }
        try { old?.close() } catch (_: IOException) {}
        Thread({ readLoop(s) }, "baybin-read").apply { isDaemon = true }.start()
        main.post { setLink(true) }
        post(Proto.HELLO, Proto.strings(BuildConfig.VERSION_NAME))
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val input = DataInputStream(BufferedInputStream(s.inputStream, 16 * 1024))
            while (true) {
                val f = Proto.read(input)
                val p = f.payload
                when (f.type) {
                    Proto.HELLO -> {
                        val version = Proto.stringsAt(p, 0, 1)[0]
                        main.post {
                            phoneAppVersion = version
                            listener?.onPhoneAppSeen(version)
                        }
                    }
                    Proto.RESULT -> {
                        val reqId = Proto.intAt(p, 0)
                        val kind = Proto.intAt(p, 4)
                        val (line1, line2) = Proto.stringsAt(p, 8, 2)
                        main.post { listener?.onResult(reqId, kind, line1, line2) }
                    }
                    Proto.PROBE_ACK -> {
                        val reqId = Proto.intAt(p, 0)
                        val received = Proto.intAt(p, 4)
                        main.post { listener?.onProbeAck(reqId, received) }
                    }
                    else -> Log.w(TAG, "unknown frame type ${f.type}")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "link down: ${e.message}")
        } catch (e: RuntimeException) { // a malformed frame must not take the app down
            Log.w(TAG, "bad frame, dropping the link", e)
        }
        detach(s)
    }

    private fun detach(s: BluetoothSocket) {
        try { s.close() } catch (_: IOException) {}
        val wasCurrent = synchronized(lock) {
            if (socket === s) {
                socket = null
                out = null
                true
            } else {
                false
            }
        }
        if (wasCurrent) main.post { setLink(false) }
    }

    /** Queues one frame; [done] gets (sent, ms spent writing) on the main thread. */
    private fun post(type: Int, vararg parts: ByteArray, done: ((Boolean, Long) -> Unit)? = null) {
        writer.execute {
            val o = synchronized(lock) { out }
            val t = SystemClock.elapsedRealtime()
            val ok = o != null && try {
                Proto.write(o, type, *parts)
                true
            } catch (e: IOException) {
                Log.w(TAG, "send failed: ${e.message}")
                synchronized(lock) { socket }?.let { try { it.close() } catch (_: IOException) {} }
                false
            }
            val ms = SystemClock.elapsedRealtime() - t
            if (done != null) main.post { done(ok, ms) }
        }
    }

    /** "A photo is coming": lets the phone warm up its cloud connection during the capture. */
    fun sendPrepare() = post(Proto.PREPARE)

    fun sendScan(reqId: Int, jpeg: ByteArray, rotation: Int, done: (Boolean, Long) -> Unit) =
        post(Proto.SCAN, Proto.ints(reqId, rotation), jpeg, done = done)

    /** Transfer test: [size] filler bytes, answered by PROBE_ACK. */
    fun sendProbe(reqId: Int, size: Int, done: (Boolean, Long) -> Unit) =
        post(Proto.PROBE, Proto.ints(reqId), ByteArray(size), done = done)

    private fun setLink(up: Boolean) {
        if (linkUp == up) return
        linkUp = up
        if (!up) phoneAppVersion = null
        listener?.onLinkChanged(up)
    }

    companion object {
        private const val TAG = "BayBin"

        @Volatile private var instance: PhoneLink? = null

        fun get(context: Context): PhoneLink =
            instance ?: synchronized(this) { instance ?: PhoneLink(context).also { instance = it } }
    }
}
