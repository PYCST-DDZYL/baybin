package com.baybin.phone

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.baybin.protocol.Proto
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Process-wide wiring: rules, one glasses link, one pipeline, one worker thread.
 * Lives in the Application so a recreated activity doesn't double anything.
 */
class App : Application(), GlassesLink.Listener {

    /** The main screen while it is visible. */
    interface Ui {
        fun refresh()
        fun onDeviceFound(device: BluetoothDevice)
    }

    lateinit var rules: Rules
        private set
    lateinit var link: GlassesLink
        private set
    lateinit var pipeline: Pipeline
        private set

    /**
     * Each city keeps the last answer its own rules produced. Switching on the phone
     * applies the new city's file to the item already recognized. It does not copy another
     * city's bin, and the city left behind is not rewritten.
     */
    private class CitySlot {
        var answer: Pipeline.Answer? = null
        var photo: Bitmap? = null
        var source: String = ""
    }

    private val slots = HashMap<String, CitySlot>()

    /** True while a scan or a gallery photo is in the pipeline. The selected city shows "识别中…". */
    @Volatile private var busy: Boolean = false

    var ui: Ui? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** A [Rules.CITY_IDS] id; picked on the main screen. The glasses are not told. */
    var city: String
        get() = prefs.getString("city", null)?.takeIf { it in Rules.CITY_IDS } ?: Rules.CITY_IDS[0]
        set(value) { prefs.edit().putString("city", value).apply() }

    /** "zh" or "en". Display only; the logged bin name stays English. */
    var lang: String
        get() = prefs.getString("lang", null)?.takeIf { it == "zh" || it == "en" } ?: "zh"
        set(value) { prefs.edit().putString("lang", value).apply() }

    /** True when this phone has a key saved. The key itself is never logged or shown. */
    fun hasQwenKey(): Boolean = qwenKey().isNotBlank()

    /** Encrypts the key into app-private storage. The caller wipes the text field. */
    fun saveQwenKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(KEY_PREF, seal(trimmed)).commit()
        pipeline = newPipeline()
        EventLog.add("Qwen: key saved on this phone")
    }

    private fun qwenKey(): String {
        val stored = prefs.getString(KEY_PREF, null).orEmpty()
        if (stored.isEmpty()) return ""
        return try {
            unseal(stored)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * One-shot file dropped beside the app by the owner.
     * Read, encrypt, overwrite, delete. Never log the text.
     */
    private fun absorbKeyFile() {
        val file = File(filesDir, KEY_FILE)
        if (!file.isFile) return
        val text = try {
            file.readText().trim()
        } catch (_: Exception) {
            ""
        }
        // A short or binary leftover must not replace a key that is already sealed.
        if (text.length < 20 || text.any { it <= ' ' || it == '\u007f' }) {
            wipe(file)
            return
        }
        val sealed = try {
            seal(text)
        } catch (_: Exception) {
            return
        }
        if (prefs.edit().putString(KEY_PREF, sealed).commit()) wipe(file)
    }

    /** AES/GCM via Android Keystore. The ciphertext is what prefs store, not the key. */
    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unseal(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        if (raw.size <= GCM_IV_BYTES) return ""
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8).trim()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wipe(file: File) {
        try {
            file.writeBytes(ByteArray(0))
        } catch (_: Exception) {
        }
        file.delete()
    }

    private fun newPipeline(): Pipeline {
        val key = qwenKey()
        val qwen = key.takeIf { it.isNotBlank() }?.let {
            QwenClient(BuildConfig.QWEN_BASE_URL, it, BuildConfig.QWEN_MODEL)
        }
        return Pipeline(this, rules, qwen)
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "baybin-pipeline") }
    private val main = Handler(Looper.getMainLooper())

    /** Bumps when a new scan or gallery job starts. An older job must not clear [busy]. */
    private var busyToken = 0

    /** Debug builds: pair with the next glasses a scan finds. Set by FIND. */
    private var autoPair = false

    override fun onCreate() {
        super.onCreate()
        rules = Rules.load(this)
        absorbKeyFile()
        EventLog.add(if (qwenKey().isNotBlank()) "Qwen: key on this phone, ${rules.items.size} items, city $city" else "Qwen: no API key")
        pipeline = newPipeline()
        link = GlassesLink(this).also { it.listener = this }
        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    // ---- GlassesLink.Listener (main thread) ----

    override fun onScan(reqId: Int, jpeg: ByteArray, rotation: Int) {
        val tRecv = SystemClock.elapsedRealtime()
        val cityNow = city
        val token = startBusy()
        EventLog.add("scan $reqId: ${jpeg.size} B, rotation $rotation, city $cityNow")
        worker.execute {
            val a = try {
                pipeline.run(jpeg, rotation, cityNow)
            } catch (e: Exception) {
                EventLog.add("scan $reqId failed: ${e.message}")
                main.post {
                    finishBusy(token)
                    ui?.refresh()
                }
                return@execute
            }
            val thumb = thumbnail(jpeg, rotation)
            main.post {
                // The phone city at this moment wins. A quick switch while this was running
                // is that city's own rule, not the one the scan started with. The glasses
                // get this same bin once. A later switch updates only the phone.
                val shown = settle(cityNow, a, thumb, "glasses", token)
                val name = shown.itemId?.let { rules.item(it)?.name }
                val sent = link.sendResult(
                    reqId, shown.kind,
                    BinFace.lens(shown.line1, Lang.zh(lang)),
                    Lang.lensDetail(shown.itemId, name, shown.line2, lang),
                )
                EventLog.add("BB_RESULT id=$reqId ${describe(shown)} phone=${SystemClock.elapsedRealtime() - tRecv} sent=$sent " +
                    "| ${shown.line1} | ${shown.line2}")
            }
        }
    }

    override fun onProbe(reqId: Int, receivedBytes: Int) {
        link.send(Proto.PROBE_ACK, Proto.ints(reqId, receivedBytes))
    }

    override fun onPrepare() = pipeline.warmUp()

    override fun onHello(glassesVersion: String) {
        EventLog.add("glasses app $glassesVersion connected")
        ui?.refresh()
    }

    override fun onState(state: GlassesLink.State, detail: String) {
        ui?.refresh()
    }

    override fun onDeviceFound(device: BluetoothDevice) {
        if (autoPair) {
            autoPair = false
            EventLog.add("debug: pairing with ${device.address}")
            link.pair(device)
        }
        ui?.onDeviceFound(device)
    }

    // ---- gallery test mode: same pipeline, no glasses ----

    /** A photo from the phone's gallery, made into what the glasses would send (≤1024px, q80), then classified. */
    fun classifyUri(uri: Uri) {
        classify(uri.lastPathSegment ?: "photo") { ImageDecoder.createSource(contentResolver, uri) }
    }

    private fun classifyFile(file: File) {
        if (!file.isFile) {
            EventLog.add("BB_GALLERY file=${file.name} missing")
            return
        }
        classify(file.name) { ImageDecoder.createSource(file) }
    }

    private fun classify(label: String, source: () -> ImageDecoder.Source) {
        val cityNow = city
        val token = startBusy()
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            val jpeg = try {
                Gallery.toJpeg(source(), GALLERY_SIDE)
            } catch (e: Exception) { // not an image, unsupported format, file gone
                EventLog.add("BB_GALLERY file=$label unreadable: ${e.message}")
                main.post {
                    finishBusy(token)
                    ui?.refresh()
                }
                return@execute
            }
            classifyNow(jpeg, label, t0, cityNow, token)
        }
    }

    /** Worker thread. [cityNow] is the city captured when the job started, so the log matches the answer. */
    private fun classifyNow(jpeg: ByteArray, label: String, t0: Long, cityNow: String, token: Int) {
        val a = try {
            pipeline.run(jpeg, 0, cityNow)
        } catch (e: Exception) {
            EventLog.add("BB_GALLERY file=$label failed: ${e.message}")
            main.post {
                finishBusy(token)
                ui?.refresh()
            }
            return
        }
        val thumb = thumbnail(jpeg, 0)
        main.post {
            val shown = settle(cityNow, a, thumb, "gallery", token)
            EventLog.add("BB_GALLERY file=$label city=${shown.cityId ?: cityNow} ${describe(shown)} total=${SystemClock.elapsedRealtime() - t0} " +
                "| ${shown.line1} | ${shown.line2}")
        }
    }

    /**
     * Main thread. The phone is the city switch. Applies [id]'s own rule to the item on screen.
     * Does not tell the glasses, and does not change the city just left.
     */
    fun onCityChanged(id: String) {
        if (id !in Rules.CITY_IDS) return
        if (id != city) {
            val from = answerFor(city)
            val photo = photoFor(city)
            val source = sourceFor(city)
            city = id
            // A scan still running will land on this city when it finishes. Don't paint the previous item first.
            if (!busy) from?.let { pipeline.forCity(it, id) }?.let { remember(id, it, photo, source) }
        }
        EventLog.add("city $id")
        ui?.refresh()
    }

    /** Main thread. Words on the phone and the next glasses result. Does not change the bin. */
    fun onLanguageChanged(id: String) {
        if (id != "zh" && id != "en") return
        if (id != lang) {
            lang = id
            EventLog.add("lang $id")
        }
        ui?.refresh()
        try {
            startForegroundService(Intent(this, LinkService::class.java))
        } catch (_: Exception) {
            // The link service is not up yet (no Bluetooth permission). The screen already switched.
        }
    }

    private fun startBusy(): Int {
        busyToken += 1
        busy = true
        ui?.refresh()
        return busyToken
    }

    /** Only the newest job may leave the "识别中…" state. */
    private fun finishBusy(token: Int) {
        if (token == busyToken) busy = false
    }

    /** The answer stored for [cityId], and only if that same city produced it. */
    fun answerFor(cityId: String): Pipeline.Answer? =
        slots[cityId]?.answer?.takeIf { it.cityId == cityId }

    fun photoFor(cityId: String): Bitmap? = slots[cityId]?.photo

    fun sourceFor(cityId: String): String = slots[cityId]?.source ?: ""

    /** True while a job is running and the phone is showing this city. */
    fun busyFor(cityId: String) = busy && cityId == city

    private fun remember(cityId: String, a: Pipeline.Answer, thumb: Bitmap?, source: String) {
        if (a.cityId != cityId) return
        val s = slots.getOrPut(cityId) { CitySlot() }
        s.answer = a
        s.source = source
        if (thumb != null) s.photo = thumb
    }

    /**
     * Keeps [decided] on the city the job started with. If the phone has since moved,
     * also stores that city's own ruling of the same item. Returns the answer to show now.
     */
    private fun settle(startCity: String, decided: Pipeline.Answer, thumb: Bitmap?, source: String, token: Int): Pipeline.Answer {
        remember(startCity, decided, thumb, source)
        val selected = city
        val moved = if (selected == startCity) null else pipeline.forCity(decided, selected)
        if (moved != null) remember(selected, moved, thumb, source)
        finishBusy(token)
        ui?.refresh()
        return if (moved != null && moved.cityId == selected) moved else decided
    }

    private fun describe(a: Pipeline.Answer) = "kind=${a.kind} item=${a.itemId} bin=${a.bin} " +
        "p=${a.pFirst?.let { String.format(Locale.US, "%.3f", it) }} calls=${a.calls} prep=${a.prepMs} model=${a.modelMs}"

    private fun thumbnail(jpeg: ByteArray, rotation: Int): Bitmap? = try {
        val small = Upright.prepare(jpeg, rotation, 320)
        BitmapFactory.decodeByteArray(small, 0, small.size)
    } catch (e: Exception) {
        null
    }

    /**
     * Debug hooks so pairing and the acceptance tests can be driven from the PC:
     *   adb shell am broadcast -a com.baybin.phone.FIND -p com.baybin.phone [--es mac AA:BB:..]
     *   adb shell am broadcast -a com.baybin.phone.DISCONNECT -p com.baybin.phone [--ez stay true]
     *   adb shell am broadcast -a com.baybin.phone.RECONNECT -p com.baybin.phone
     *   adb shell am broadcast -a com.baybin.phone.STATUS -p com.baybin.phone
     *   adb shell am broadcast -a com.baybin.phone.CITY -p com.baybin.phone --es id san_jose
     *     (id is one of cupertino, san_jose, palo_alto, los_altos)
     *   adb shell am broadcast -a com.baybin.phone.CLASSIFY -p com.baybin.phone --es file x.jpg
     *     (x.jpg in the app's private files/eval folder; tools/accept_7_gallery.py puts it there)
     */
    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_FIND -> {
                        val mac = intent.getStringExtra("mac")
                        if (!mac.isNullOrBlank()) link.connect(mac.uppercase())
                        else {
                            autoPair = true
                            link.startScan()
                        }
                    }
                    ACTION_DISCONNECT -> link.disconnect(intent.getBooleanExtra("stay", false))
                    ACTION_RECONNECT -> {
                        link.autoReconnect = true
                        link.savedMac?.let { link.connect(it) } ?: EventLog.add("debug: no saved glasses")
                    }
                    ACTION_STATUS -> EventLog.add("BB_STATUS link=${link.state} mac=${link.savedMac} " +
                        "glasses=${link.glassesVersion} city=$city online=${Net.online(this@App)}")
                    ACTION_CITY -> intent.getStringExtra("id")?.let { onCityChanged(it) }
                    ACTION_LANG -> intent.getStringExtra("id")?.let { onLanguageChanged(it) }
                    ACTION_CLASSIFY -> intent.getStringExtra("file")?.let {
                        classifyFile(File(File(filesDir, "eval"), File(it).name))
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            listOf(ACTION_FIND, ACTION_DISCONNECT, ACTION_RECONNECT, ACTION_STATUS, ACTION_CITY, ACTION_LANG, ACTION_CLASSIFY)
                .forEach { addAction(it) }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    companion object {
        /** Gallery photos are first scaled to what the glasses would send. */
        const val GALLERY_SIDE = 1024
        private const val ACTION_FIND = "com.baybin.phone.FIND"
        private const val ACTION_DISCONNECT = "com.baybin.phone.DISCONNECT"
        private const val ACTION_RECONNECT = "com.baybin.phone.RECONNECT"
        private const val ACTION_STATUS = "com.baybin.phone.STATUS"
        private const val ACTION_CITY = "com.baybin.phone.CITY"
        private const val ACTION_LANG = "com.baybin.phone.LANG"
        private const val ACTION_CLASSIFY = "com.baybin.phone.CLASSIFY"
        private const val KEY_PREF = "qwenKey"
        private const val KEY_FILE = "qwen.key"
        private const val KEY_ALIAS = "baybin-qwen"
        private const val GCM_IV_BYTES = 12
    }
}
