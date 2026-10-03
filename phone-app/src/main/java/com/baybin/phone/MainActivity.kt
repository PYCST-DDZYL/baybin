package com.baybin.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var otherButton: Button
    private lateinit var galleryButton: Button
    private lateinit var resultCard: LinearLayout
    private lateinit var resultLabel: TextView
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var sourceLink: TextView
    private lateinit var meta: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logToggle: TextView
    private lateinit var photo: ImageView
    private lateinit var deviceBox: LinearLayout
    private lateinit var regionGroup: RadioGroup
    private lateinit var cityGroup: RadioGroup
    private lateinit var langGroup: RadioGroup
    private lateinit var settingsButton: TextView
    private lateinit var keyBlock: View
    private lateinit var keyInput: EditText
    private lateinit var keySave: Button
    private lateinit var keyState: TextView
    private val devices = ArrayList<BluetoothDevice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logOpen = false
    /** City rows under California stay closed until that row is tapped, then close again after a pick. */
    private var regionOpen = false
    /** First search only: one glasses in view connects itself. A saved pair is never auto-picked. */
    private val autoPick = Runnable {
        if (isFinishing) return@Runnable
        if (app.link.savedMac != null) return@Runnable
        if (app.link.state != GlassesLink.State.SCANNING) return@Runnable
        val only = devices.singleOrNull() ?: return@Runnable
        deviceBox.visibility = View.GONE
        app.link.pair(only)
    }
    private val onCityPick = RadioGroup.OnCheckedChangeListener { _, id ->
        regionOpen = false
        app.onCityChanged(cityIdOf(id))
    }
    private val onRegionPick = RadioGroup.OnCheckedChangeListener { _, id ->
        regionOpen = true
        val cities = citiesOf(id)
        if (cities.none { it.first == app.city }) app.onCityChanged(cities.first().first)
        else refresh()
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
        sourceLink = findViewById(R.id.source_link)
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

        deviceBox = findViewById(R.id.devices)
        scanButton = findViewById(R.id.scan)
        scanButton.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            val mac = app.link.savedMac
            if (mac == null) beginScan()
            else if (app.link.state != GlassesLink.State.CONNECTED &&
                app.link.state != GlassesLink.State.CONNECTING &&
                app.link.state != GlassesLink.State.SCANNING
            ) {
                app.link.connect(mac)
            }
        }
        otherButton = findViewById(R.id.connect_other)
        otherButton.setOnClickListener { if (ensurePermissions()) beginScan() }
        galleryButton = findViewById(R.id.gallery)
        galleryButton.setOnClickListener { pickPhoto() }

        regionGroup = findViewById(R.id.region)
        cityGroup = findViewById(R.id.city)
        // Check before the listeners so opening the screen does not count as a city change.
        regionGroup.check(regionOf(app.city))
        cityGroup.check(radioOf(app.city))
        regionGroup.setOnCheckedChangeListener(onRegionPick)
        cityGroup.setOnCheckedChangeListener(onCityPick)
        // California is already checked, so a later tap does not fire the group listener.
        findViewById<View>(R.id.region_ca).setOnClickListener {
            regionOpen = !regionOpen
            showCities(regionOf(app.city))
        }
        // Tapping the city that is already selected does not fire OnCheckedChange either.
        CA_CITIES.forEach { (_, radio) ->
            findViewById<View>(radio).setOnClickListener {
                if (regionOpen && cityGroup.checkedRadioButtonId == radio) {
                    regionOpen = false
                    showCities(regionOf(app.city))
                }
            }
        }
        showCities(regionOf(app.city))

        langGroup = findViewById(R.id.lang)
        langGroup.check(if (app.lang == "en") R.id.lang_en else R.id.lang_zh)
        langGroup.setOnCheckedChangeListener(onLangPick)

        settingsButton = findViewById(R.id.settings)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        keyBlock = findViewById(R.id.key_block)
        keyInput = findViewById(R.id.key_input)
        keySave = findViewById(R.id.key_save)
        keyState = findViewById(R.id.key_state)
        keySave.setOnClickListener {
            val typed = keyInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty()) {
                app.saveQwenKey(typed)
                keyInput.text.clear()
            }
            refresh()
        }

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

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoPick)
        super.onDestroy()
    }

    // ---- App.Ui ----

    override fun refresh() {
        val link = app.link
        val lang = app.lang
        title.text = Lang.s(lang, "title")
        val saved = link.savedMac != null
        val busy = link.state == GlassesLink.State.SCANNING || link.state == GlassesLink.State.CONNECTING
        val up = link.state == GlassesLink.State.CONNECTED
        scanButton.isEnabled = !busy && !up
        scanButton.alpha = if (scanButton.isEnabled) 1f else 0.45f
        scanButton.text = when {
            up -> Lang.s(lang, "connected_btn")
            link.state == GlassesLink.State.SCANNING -> Lang.s(lang, "scanning")
            link.state == GlassesLink.State.CONNECTING -> Lang.s(lang, "connecting")
            saved -> Lang.s(lang, "connect")
            else -> Lang.s(lang, "scan")
        }
        otherButton.visibility = if (saved) View.VISIBLE else View.GONE
        otherButton.text = Lang.s(lang, "connect_other")
        otherButton.isEnabled = !busy
        otherButton.alpha = if (otherButton.isEnabled) 1f else 0.45f
        galleryButton.text = Lang.s(lang, "gallery")
        settingsButton.text = Lang.s(lang, "settings")
        val showKey = !app.hasQwenKey()
        keyBlock.visibility = if (showKey) View.VISIBLE else View.GONE
        if (showKey) {
            keyInput.hint = Lang.s(lang, "key_hint")
            keySave.text = Lang.s(lang, "key_save")
            keyState.text = Lang.s(lang, "key_missing")
        }
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
            ?: Lang.s(lang, if (saved) "paired_hint" else "first_use")
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(when (link.state) {
            GlassesLink.State.CONNECTED -> R.color.dot_on
            GlassesLink.State.CONNECTING, GlassesLink.State.SCANNING -> R.color.dot_wait
            else -> R.color.dot_off
        }))
        cityHint.text = Lang.s(lang, hintKey(app.city))
        val wantLang = if (lang == "en") R.id.lang_en else R.id.lang_zh
        if (langGroup.checkedRadioButtonId != wantLang) {
            langGroup.setOnCheckedChangeListener(null)
            langGroup.check(wantLang)
            langGroup.setOnCheckedChangeListener(onLangPick)
        }
        val wantRegion = regionOf(app.city)
        if (regionGroup.checkedRadioButtonId != wantRegion) {
            regionGroup.setOnCheckedChangeListener(null)
            regionGroup.check(wantRegion)
            regionGroup.setOnCheckedChangeListener(onRegionPick)
        }
        showCities(wantRegion)
        val wantCity = radioOf(app.city)
        if (cityGroup.checkedRadioButtonId != wantCity) {
            cityGroup.setOnCheckedChangeListener(null)
            cityGroup.check(wantCity)
            cityGroup.setOnCheckedChangeListener(onCityPick)
        }
        if (link.state != GlassesLink.State.SCANNING) mainHandler.removeCallbacks(autoPick)
        showDevices()

        val a = app.answerFor(app.city)
        if (app.busyFor(app.city)) {
            resultLabel.text = Lang.s(lang, "throw")
            line1.text = Lang.s(lang, "working")
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            line2.text = ""
            meta.text = ""
            showPage(null)
            paint(R.color.card, onLight = true)
        } else if (a == null) {
            resultLabel.text = Lang.s(lang, "how")
            line1.text = Lang.s(lang, "no_result")
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            line2.text = Lang.s(lang, "how_body")
            meta.text = ""
            showPage(null)
            paint(R.color.card, onLight = true)
        } else {
            val face = BinFace.of(a.cityId ?: app.city, a.line1, Lang.zh(lang))
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
            val source = when (val src = app.sourceFor(app.city)) {
                "glasses" -> Lang.s(lang, "source_glasses")
                "gallery" -> Lang.s(lang, "source_gallery")
                else -> src.takeIf { it.isNotEmpty() }
            }
            meta.text = listOfNotNull(source, name, a.line1).joinToString("  ·  ")
            showPage(a.sourceUrl)
            paint(face.background, face.onLight)
        }
        val bmp = app.photoFor(app.city)
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
        val link = getColor(if (onLight) R.color.accent else R.color.on_bin)
        sourceLink.setTextColor(link)
        // Spans go on after setTextColor, which would otherwise paint the whole line one color.
        linkify(line2, link)
    }

    private fun showPage(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            sourceLink.visibility = View.GONE
            sourceLink.setOnClickListener(null)
            return
        }
        sourceLink.visibility = View.VISIBLE
        sourceLink.text = Lang.s(app.lang, "official")
        sourceLink.setOnClickListener { openPage(url) }
    }

    private fun linkify(view: TextView, color: Int) {
        val raw = view.text?.toString().orEmpty()
        if (raw.isEmpty()) {
            view.movementMethod = null
            return
        }
        val span = SpannableString(raw)
        var linked = false
        for (match in DOMAIN.findAll(raw)) {
            val token = match.value.trimEnd('.', ',', ';', ')')
            if (token.length < 4 || '.' !in token) continue
            val start = match.range.first
            span.setSpan(PageLink(token, color), start, start + token.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            linked = true
        }
        if (!linked) {
            view.movementMethod = null
            return
        }
        view.text = span
        view.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openPage(url: String) {
        val full = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
        } catch (_: ActivityNotFoundException) {
            EventLog.add("can't open page")
        }
    }

    private inner class PageLink(private val url: String, private val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) = openPage(url)

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceFound(device: BluetoothDevice) {
        if (devices.any { it.address == device.address }) return
        devices.add(device)
        if (app.link.savedMac == null && app.link.state == GlassesLink.State.SCANNING) {
            mainHandler.removeCallbacks(autoPick)
            if (devices.size == 1) mainHandler.postDelayed(autoPick, 700)
        }
        showDevices()
    }

    private fun beginScan() {
        mainHandler.removeCallbacks(autoPick)
        devices.clear()
        showDevices()
        app.link.startScan()
    }

    @SuppressLint("MissingPermission")
    private fun showDevices() {
        deviceBox.removeAllViews()
        val hide = devices.isEmpty() ||
            app.link.state == GlassesLink.State.CONNECTED ||
            app.link.state == GlassesLink.State.CONNECTING
        deviceBox.visibility = if (hide) View.GONE else View.VISIBLE
        if (hide) return
        val lang = app.lang
        val showMac = devices.size > 1
        val ripple = TypedValue()
        val hasRipple = theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        devices.forEachIndexed { index, device ->
            if (index > 0) {
                deviceBox.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    }
                    setBackgroundColor(getColor(R.color.line))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
                if (hasRipple) foreground = getDrawable(ripple.resourceId)
                setOnClickListener {
                    mainHandler.removeCallbacks(autoPick)
                    deviceBox.visibility = View.GONE
                    app.link.pair(device)
                }
            }
            row.addView(TextView(this).apply {
                text = device.name?.takeIf { it.isNotBlank() } ?: Lang.s(lang, "glasses_name")
                setTextColor(getColor(R.color.ink))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = Lang.s(lang, "tap_connect")
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            })
            if (showMac) {
                row.addView(TextView(this).apply {
                    text = device.address
                    setTextColor(getColor(R.color.muted))
                    textSize = 12f
                })
            }
            deviceBox.addView(row)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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

    /** Cities under the selected state. Another state is one more radio and its own list. */
    private fun citiesOf(regionId: Int): List<Pair<String, Int>> = when (regionId) {
        else -> CA_CITIES
    }

    private fun regionOf(cityId: String): Int = when {
        CA_CITIES.any { it.first == cityId } -> R.id.region_ca
        else -> R.id.region_ca
    }

    private fun showCities(regionId: Int) {
        val shown = citiesOf(regionId).map { it.second }.toSet()
        val keep = radioOf(app.city)
        CA_CITIES.forEach { (_, radio) ->
            val visible = radio in shown && (regionOpen || radio == keep)
            findViewById<View>(radio).visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun cityIdOf(radioId: Int) = when (radioId) {
        R.id.city_sanjose -> "san_jose"
        R.id.city_paloalto -> "palo_alto"
        R.id.city_losaltos -> "los_altos"
        R.id.city_berkeley -> "berkeley"
        else -> "cupertino"
    }

    private fun radioOf(cityId: String) = when (cityId) {
        "san_jose" -> R.id.city_sanjose
        "palo_alto" -> R.id.city_paloalto
        "los_altos" -> R.id.city_losaltos
        "berkeley" -> R.id.city_berkeley
        else -> R.id.city_cupertino
    }

    private fun hintKey(cityId: String) = when (cityId) {
        "san_jose" -> "hint_sj"
        "palo_alto" -> "hint_paloalto"
        "los_altos" -> "hint_losaltos"
        "berkeley" -> "hint_berkeley"
        else -> "hint_cupertino"
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PICK = 2
        private val CA_CITIES = listOf(
            "cupertino" to R.id.city_cupertino,
            "san_jose" to R.id.city_sanjose,
            "palo_alto" to R.id.city_paloalto,
            "los_altos" to R.id.city_losaltos,
            "berkeley" to R.id.city_berkeley,
        )
        /** A domain written in a reason, such as hhw.org. Not an email, and not the long source address. */
        private val DOMAIN = Regex(
            """(?i)(?<![@\w])(?:https?://)?(?:[a-z0-9-]+\.)+(?:org|gov|com|net)\b(?:/[^\s，。,)]*)?""",
        )
    }
}
