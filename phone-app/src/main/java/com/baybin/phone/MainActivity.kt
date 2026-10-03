package com.baybin.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Main screen: glasses connection, city, gallery test mode (no glasses needed), the last
 * answer and the event log. Everything that matters lives in [App]; this only displays it.
 */
class MainActivity : Activity(), App.Ui {

    private lateinit var app: App
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var statusHint: TextView
    private lateinit var statusDot: View
    private lateinit var cityHint: TextView
    private lateinit var scanButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var galleryButton: Button
    private lateinit var resultCard: LinearLayout
    private lateinit var resultLabel: TextView
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var meta: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logToggle: TextView
    private lateinit var photo: ImageView
    private lateinit var deviceList: ListView
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var cityGroup: RadioGroup
    private lateinit var langGroup: RadioGroup
    private val devices = ArrayList<BluetoothDevice>()
    private var logOpen = false
    private val onCityPick = RadioGroup.OnCheckedChangeListener { _, id ->
        app.onCityChanged(if (id == R.id.city_sanjose) "san_jose" else "cupertino")
    }
    private val onLangPick = RadioGroup.OnCheckedChangeListener { _, id ->
        app.onLanguageChanged(if (id == R.id.lang_en) "en" else "zh")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        app = application as App
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        statusHint = findViewById(R.id.status_hint)
        statusDot = findViewById(R.id.status_dot)
        cityHint = findViewById(R.id.city_hint)
        resultCard = findViewById(R.id.result)
        resultLabel = findViewById(R.id.result_label)
        line1 = findViewById(R.id.line1)
        line2 = findViewById(R.id.line2)
        meta = findViewById(R.id.meta)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        logToggle = findViewById(R.id.log_toggle)
        photo = findViewById(R.id.photo)
        logToggle.setOnClickListener {
            logOpen = !logOpen
            logScroll.visibility = if (logOpen) View.VISIBLE else View.GONE
            logToggle.text = Lang.s(app.lang, if (logOpen) "log_hide" else "log")
        }

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        deviceList = findViewById<ListView>(R.id.devices).apply {
            adapter = deviceAdapter
            setOnItemClickListener { _, _, pos, _ ->
                val device = devices.getOrNull(pos) ?: return@setOnItemClickListener
                visibility = View.GONE
                app.link.pair(device)
            }
        }
        scanButton = findViewById(R.id.scan)
        scanButton.setOnClickListener {
            if (ensurePermissions()) {
                devices.clear()
                deviceAdapter.clear()
                app.link.startScan()
            }
        }
        reconnectButton = findViewById(R.id.reconnect)
        reconnectButton.setOnClickListener {
            if (ensurePermissions()) app.link.savedMac?.let { app.link.connect(it) }
                ?: EventLog.add(Lang.s(app.lang, "no_pair"))
        }
        galleryButton = findViewById(R.id.gallery)
        galleryButton.setOnClickListener { pickPhoto() }

        cityGroup = findViewById(R.id.city)
        // Check before the listener so opening the screen does not count as a city change.
        cityGroup.check(if (app.city == "san_jose") R.id.city_sanjose else R.id.city_cupertino)
        cityGroup.setOnCheckedChangeListener(onCityPick)

        langGroup = findViewById(R.id.lang)
        langGroup.check(if (app.lang == "en") R.id.lang_en else R.id.lang_zh)
        langGroup.setOnCheckedChangeListener(onLangPick)

        if (ensurePermissions()) startLinkService()
    }

    override fun onResume() {
        super.onResume()
        app.ui = this
        EventLog.listener = { log.text = EventLog.text() }
        refresh()
        // Opening this screen should not wait out a long retry. The glasses still only listen.
        app.link.reconnectNow()
    }

    override fun onPause() {
        app.ui = null
        EventLog.listener = null
        super.onPause()
    }

    // ---- App.Ui ----

    override fun refresh() {
        val link = app.link
        val lang = app.lang
        title.text = Lang.s(lang, "title")
        scanButton.text = Lang.s(lang, "scan")
        reconnectButton.text = Lang.s(lang, "reconnect")
        galleryButton.text = Lang.s(lang, "gallery")
        logToggle.text = Lang.s(lang, if (logOpen) "log_hide" else "log")
        photo.contentDescription = Lang.s(lang, "photo")
        status.text = when (link.state) {
            GlassesLink.State.CONNECTED -> Lang.s(lang, "connected")
            GlassesLink.State.CONNECTING -> Lang.s(lang, "connecting")
            GlassesLink.State.SCANNING -> Lang.s(lang, "scanning")
            GlassesLink.State.FAILED -> Lang.s(lang, if (link.savedMac != null) "failed_app" else "failed_none")
            GlassesLink.State.DISCONNECTED -> Lang.s(lang, "disconnected")
        }
        statusHint.text = link.glassesVersion?.let { Lang.s(lang, "glasses_ver").format(it) }
            ?: Lang.s(lang, "first_use")
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(when (link.state) {
            GlassesLink.State.CONNECTED -> R.color.dot_on
            GlassesLink.State.CONNECTING, GlassesLink.State.SCANNING -> R.color.dot_wait
            else -> R.color.dot_off
        }))
        cityHint.text = Lang.s(lang, if (app.city == "san_jose") "hint_sj" else "hint_cupertino")
        val wantLang = if (lang == "en") R.id.lang_en else R.id.lang_zh
        if (langGroup.checkedRadioButtonId != wantLang) {
            langGroup.setOnCheckedChangeListener(null)
            langGroup.check(wantLang)
            langGroup.setOnCheckedChangeListener(onLangPick)
        }
        val wantCity = if (app.city == "san_jose") R.id.city_sanjose else R.id.city_cupertino
        if (cityGroup.checkedRadioButtonId != wantCity) {
            cityGroup.setOnCheckedChangeListener(null)
            cityGroup.check(wantCity)
            cityGroup.setOnCheckedChangeListener(onCityPick)
        }
        if (link.state == GlassesLink.State.CONNECTED) deviceList.visibility = View.GONE

        val a = app.lastAnswer
        if (app.busy) {
            resultLabel.text = Lang.s(lang, "throw")
            line1.text = Lang.s(lang, "working")
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            line2.text = ""
            meta.text = ""
            paint(R.color.card, onLight = true)
        } else if (a == null) {
            resultLabel.text = Lang.s(lang, "how")
            line1.text = Lang.s(lang, "no_result")
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            line2.text = Lang.s(lang, "how_body")
            meta.text = ""
            paint(R.color.card, onLight = true)
        } else {
            val face = BinFace.of(app.city, a.line1, Lang.zh(lang))
            resultLabel.text = Lang.s(lang, "throw")
            line1.text = face.title
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (face.title.length <= 3) 44f else 28f)
            val reason = Lang.reason(a.line2, lang)
            line2.text = when {
                face.note != null && reason.isNotBlank() -> face.note + "\n" + reason
                face.note != null -> face.note
                else -> reason
            }
            val name = a.itemId?.let { app.rules.item(it)?.name }
            val source = when (app.lastSource) {
                "glasses" -> Lang.s(lang, "source_glasses")
                "gallery" -> Lang.s(lang, "source_gallery")
                else -> app.lastSource.takeIf { it.isNotEmpty() }
            }
            meta.text = listOfNotNull(source, name, a.line1).joinToString("  ·  ")
            paint(face.background, face.onLight)
        }
        val bmp = app.lastPhoto
        if (bmp == null) photo.visibility = View.GONE
        else {
            photo.visibility = View.VISIBLE
            photo.setImageBitmap(bmp)
        }
        log.text = EventLog.text()
    }

    private fun paint(background: Int, onLight: Boolean) {
        resultCard.backgroundTintList = ColorStateList.valueOf(getColor(background))
        val main = getColor(if (onLight) R.color.ink else R.color.on_bin)
        val soft = getColor(if (onLight) R.color.muted else R.color.on_bin_soft)
        resultLabel.setTextColor(soft)
        line1.setTextColor(main)
        line2.setTextColor(main)
        meta.setTextColor(soft)
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceFound(device: BluetoothDevice) {
        devices.add(device)
        deviceAdapter.add("${device.name ?: "?"}  ${device.address}")
        deviceList.visibility = View.VISIBLE
    }

    // ---- gallery test mode ----

    private fun pickPhoto() {
        app.pipeline.warmUp() // the cloud connections are ready by the time a photo is picked
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 1)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        }
        intent.type = "image/*"
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            EventLog.add(Lang.s(app.lang, "gallery_closed"))
        }
    }

    @Deprecated("Activity result API needs AndroidX; this app has none")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        // Samsung's photo picker often leaves data null and puts the uri in clipData only.
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            EventLog.add(Lang.s(app.lang, "gallery_empty"))
            return
        }
        app.classifyUri(uri)
    }

    // ---- permissions ----

    private fun ensurePermissions(): Boolean {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val bluetooth = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_PERMS && bluetooth) {
            EventLog.add("permissions granted")
            startLinkService()
        } else {
            EventLog.add(Lang.s(app.lang, "no_bt"))
        }
    }

    private fun startLinkService() {
        try {
            startForegroundService(Intent(this, LinkService::class.java))
        } catch (e: Exception) {
            EventLog.add("can't start link service: ${e.message}")
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
    }
}
