package com.baybin.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.baybin.protocol.Proto
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone end of the link: an RFCOMM socket to the glasses app (frame layout in [Proto]).
 *
 * First-time setup needs the glasses' Bluetooth (classic) address. Rokid glasses advertise a
 * BLE service; Rokid's CXR-M SDK reads the address from it ([pair]). That step works without
 * a licence; the SDK's own message channel (`connectBluetooth`) does not, so after learning
 * the address we disconnect the SDK and open our own socket. The address is saved, and
 * while [autoReconnect] is on (the foreground service) a dropped link is retried.
 *
 * Messages from the glasses reach [listener] on the main thread.
 */
class GlassesLink(private val context: Context) {

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onDeviceFound(device: BluetoothDevice)
        fun onScan(reqId: Int, jpeg: ByteArray, rotation: Int)
        fun onProbe(reqId: Int, receivedBytes: Int)
        fun onHello(glassesVersion: String)
        /** A scan started on the glasses; the photo follows in about a second. Reader thread. */
        fun onPrepare()
    }

    var listener: Listener? = null

    /** Main thread. */
    var state = State.DISCONNECTED
        private set

    /** Version the glasses app sent in its HELLO. Main thread. */
    var glassesVersion: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "baybin-send") }
    private val connecting = AtomicBoolean(false)
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    private var scanning: ScanCallback? = null
    private val seen = HashSet<String>()
    private var backoffMs = FIRST_RETRY_MS
    /** One timeout for the current scan. A fresh lambda every search used to stack, so the first one ended the second search early. */
    private val scanTimeout = Runnable { cancelScan(restore = true) }

    /** Classic Bluetooth address of the glasses from an earlier setup. */
    val savedMac: String? get() = prefs.getString(KEY_MAC, null)

    val isConnected get() = state == State.CONNECTED

    /** Keep the link up (retry with backoff). On while the foreground service runs. */
    var autoReconnect = false
        set(value) {
            field = value
            main.removeCallbacks(retry)
            if (value && state != State.CONNECTED) main.post(retry)
        }

    private val retry = Runnable {
        val mac = savedMac
        if (autoReconnect && mac != null && state != State.CONNECTED) connect(mac)
    }

    /**
     * Dial the saved glasses now. Used when the phone screen comes back, so a backoff
     * (the glasses app was closed) does not make the user wait. Does not start a search
     * and does not interrupt a connect that is already in progress.
     */
    fun reconnectNow() {
        if (!autoReconnect || savedMac == null) return
        backoffMs = FIRST_RETRY_MS
        if (state == State.CONNECTED || state == State.SCANNING || state == State.CONNECTING) return
        main.removeCallbacks(retry)
        main.post(retry)
    }

    // ---- discovery ----

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter
        if (a == null) {
            cancelScan(restore = false)
            setState(State.FAILED, "no Bluetooth adapter")
            return
        }
        if (!a.isEnabled) {
            cancelScan(restore = false)
            setState(State.FAILED, "Bluetooth is off")
            return
        }
        // Don't restore CONNECTED here: we are about to set SCANNING, and a stacked timeout must die.
        cancelScan(restore = false)
        seen.clear()
        try {
            // Glasses paired in Android's Bluetooth settings show up right away (their address is the classic one).
            a.bondedDevices?.filter { it.looksLikeGlasses() }?.forEach { report(it) }
            val scanner = a.bluetoothLeScanner
            if (scanner == null) {
                setState(State.FAILED, "Bluetooth is off")
                return
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = report(result.device)
                override fun onScanFailed(errorCode: Int) {
                    if (scanning == null) return
                    scanning = null
                    main.removeCallbacks(scanTimeout)
                    if (state == State.SCANNING) setState(State.FAILED, "BLE scan failed: $errorCode")
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(ROKID_SERVICE_UUID)).build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().build(), cb)
            scanning = cb
            setState(State.SCANNING, "looking for glasses…")
            main.postDelayed(scanTimeout, SCAN_MS)
        } catch (e: SecurityException) {
            scanning = null
            main.removeCallbacks(scanTimeout)
            setState(State.FAILED, "Bluetooth permission missing")
        }
    }

    /**
     * Stops the BLE scan and its timeout.
     * [restore] is true only when the scan ends by itself. The RFCOMM socket is separate from
     * the scan: if it is still up, the screen goes back to connected. Restoring that from
     * [pair] or a new [startScan] would make [connect] see CONNECTED and ignore the glasses just picked.
     */
    @SuppressLint("MissingPermission")
    private fun cancelScan(restore: Boolean) {
        main.removeCallbacks(scanTimeout)
        val cb = scanning
        scanning = null
        if (cb != null) {
            try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        }
        if (restore && state == State.SCANNING) {
            if (socketUp()) setState(State.CONNECTED, "scan finished")
            else setState(State.DISCONNECTED, "scan finished")
        }
    }

    private fun socketUp(): Boolean = synchronized(lock) { socket?.isConnected == true }

    private fun report(device: BluetoothDevice) {
        if (!seen.add(device.address)) return
        main.post { listener?.onDeviceFound(device) }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.looksLikeGlasses(): Boolean {
        val n = name?.lowercase() ?: return false
        return "glasses" in n || "rokid" in n
    }

    /** First-time setup for a device from [startScan]: learn its classic address, then [connect]. */
    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice) {
        // Leave state as SCANNING. Restoring CONNECTED would make connect() return without switching glasses.
        cancelScan(restore = false)
        try {
            if (adapter?.bondedDevices?.contains(device) == true) return connect(device.address)
        } catch (e: SecurityException) {
            setState(State.FAILED, "Bluetooth permission missing")
            return
        }
        setState(State.CONNECTING, "asking ${device.address} for its Bluetooth address")
        val api = CxrApi.getInstance()
        try {
            try { api.deinitBluetooth() } catch (_: Exception) {}
            api.initBluetooth(context, device, object : BluetoothStatusCallback {
                override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int) {
                    if (macAddress.isNullOrEmpty()) return
                    main.post {
                        try { api.deinitBluetooth() } catch (_: Exception) {}
                        EventLog.add("glasses Bluetooth address: $macAddress")
                        connect(macAddress)
                    }
                }

                override fun onConnected() {}
                override fun onInActiveConnected(a: String?, b: String?) {}
                override fun onDisconnected() {}
                override fun onFailed(code: ValueUtil.CxrBluetoothErrorCode?) {
                    main.post { if (state == State.CONNECTING && !connecting.get()) setState(State.FAILED, "glasses didn't answer: $code") }
                }
            })
        } catch (e: Exception) {
            setState(State.FAILED, "initBluetooth: ${e.message}")
        }
    }

    // ---- connection ----

    /** Opens our socket to the glasses app at [mac] and remembers the address. */
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        // Already talking to this glasses. Opening a second socket drops the link (the glasses keep one).
        if (socketUp() && savedMac?.equals(mac, ignoreCase = true) == true) {
            cancelScan(restore = true)
            if (state != State.CONNECTED) setState(State.CONNECTED, "glasses connected")
            return
        }
        // Not "return if CONNECTED": that also blocked switching to a different glasses, and a
        // dead socket left the screen saying connected while 重连 did nothing.
        if (!connecting.compareAndSet(false, true)) return
        cancelScan(restore = false)
        val previousMac = savedMac
        prefs.edit().putString(KEY_MAC, mac).apply()
        main.removeCallbacks(retry)
        setState(State.CONNECTING, "connecting to $mac")
        Thread({
            try {
                val a = adapter ?: throw IOException("no Bluetooth adapter")
                if (!a.isEnabled) throw IOException("Bluetooth is off")
                val s = a.getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(Proto.SERVICE_UUID)
                a.cancelDiscovery()
                s.connect()
                attach(s)
            } catch (e: IOException) {
                val bluetoothOff = adapter?.isEnabled != true // LinkService reconnects when it comes back on
                connectFailed(
                    if (bluetoothOff) "Bluetooth is off" else "can't reach the glasses app (${e.message})",
                    retry = !bluetoothOff, force = bluetoothOff, previousMac = previousMac,
                )
            } catch (e: SecurityException) {
                connectFailed("Bluetooth permission missing", retry = false, force = false, previousMac = previousMac)
            } catch (e: IllegalArgumentException) {
                connectFailed("bad address $mac", retry = false, force = false, previousMac = previousMac)
            } finally {
                connecting.set(false)
            }
        }, "baybin-connect").start()
    }

    /** A failed dial must not pretend the glasses we were already talking to are gone. */
    private fun connectFailed(detail: String, retry: Boolean, force: Boolean, previousMac: String?) {
        main.post {
            if (!force && socketUp()) {
                if (previousMac != null) prefs.edit().putString(KEY_MAC, previousMac).apply()
                setState(State.CONNECTED, "still connected")
            } else {
                setState(State.FAILED, detail)
                if (retry) scheduleRetry()
            }
        }
    }

    /** Debug/test: drop the link. [stayDown] also stops automatic reconnects. */
    fun disconnect(stayDown: Boolean) {
        if (stayDown) autoReconnect = false
        val s = synchronized(lock) { socket }
        try { s?.close() } catch (_: IOException) {}
    }

    private fun attach(s: BluetoothSocket) {
        val o = DataOutputStream(BufferedOutputStream(s.outputStream, 16 * 1024))
        val old = synchronized(lock) {
            val prev = socket
            socket = s
            out = o
            prev
        }
        if (old != null && old !== s) {
            try { old.close() } catch (_: IOException) {}
        }
        Thread({ readLoop(s) }, "baybin-read").apply { isDaemon = true }.start()
        send(Proto.HELLO, Proto.strings(BuildConfig.VERSION_NAME))
        main.post {
            backoffMs = FIRST_RETRY_MS
            setState(State.CONNECTED, "glasses connected")
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val input = DataInputStream(BufferedInputStream(s.inputStream, 64 * 1024))
            while (true) {
                val f = Proto.read(input)
                val p = f.payload
                when (f.type) {
                    Proto.HELLO -> {
                        val version = Proto.stringsAt(p, 0, 1)[0]
                        main.post {
                            glassesVersion = version
                            listener?.onHello(version)
                        }
                    }
                    Proto.SCAN -> {
                        val reqId = Proto.intAt(p, 0)
                        val rotation = Proto.intAt(p, 4)
                        val jpeg = p.copyOfRange(8, p.size)
                        main.post { listener?.onScan(reqId, jpeg, rotation) }
                    }
                    Proto.PREPARE -> listener?.onPrepare()
                    Proto.PROBE -> {
                        val reqId = Proto.intAt(p, 0)
                        val received = p.size - 4
                        main.post { listener?.onProbe(reqId, received) }
                    }
                    else -> EventLog.add("unknown frame type ${f.type}")
                }
            }
        } catch (e: IOException) {
            EventLog.add("link closed: ${e.message}")
        } catch (e: RuntimeException) { // a malformed frame must not take the app down
            EventLog.add("bad frame, dropping the link: $e")
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
        if (wasCurrent) main.post {
            glassesVersion = null
            setState(State.DISCONNECTED, "glasses disconnected")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!autoReconnect || savedMac == null) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    /** Queues one frame for the writer thread. False if there is no link. */
    fun send(type: Int, vararg parts: ByteArray): Boolean {
        val o = synchronized(lock) { out } ?: return false
        writer.execute {
            try {
                Proto.write(o, type, *parts)
            } catch (e: IOException) {
                EventLog.add("send failed: ${e.message}")
                synchronized(lock) { socket }?.let { try { it.close() } catch (_: IOException) {} }
            }
        }
        return true
    }

    fun sendResult(reqId: Int, kind: Int, line1: String, line2: String) =
        send(Proto.RESULT, Proto.ints(reqId, kind), Proto.strings(line1, line2))

    private fun setState(s: State, detail: String) {
        state = s
        EventLog.add("link: $s ($detail)")
        listener?.onState(s, detail)
    }

    companion object {
        /** Rokid glasses advertise this BLE service (CXR-M docs, "Finding Bluetooth Devices"). */
        const val ROKID_SERVICE_UUID = "00009100-0000-1000-8000-00805f9b34fb"
        private const val SCAN_MS = 15_000L
        private const val FIRST_RETRY_MS = 2_000L
        /** Short on purpose: the glasses only listen, so the phone has to notice when BayBin opens. */
        private const val MAX_RETRY_MS = 4_000L
        private const val KEY_MAC = "mac"
    }
}
