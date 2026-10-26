package com.baybin.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import android.provider.Settings
import android.text.SpannableString
import android.text.Editable
import android.text.TextWatcher
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

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
    private lateinit var progress: View
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var pageScroll: ScrollView
    private var wasProcessing = false
    private enum class BluetoothAction { CONNECT, SEARCH }
    private var pendingBluetooth: BluetoothAction? = null
    private var pendingCapture: File? = null
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
        pendingCapture = CaptureFiles.restore(cacheDir, savedInstanceState?.getString("camera_file"))
        pendingBluetooth = BluetoothAction.entries.firstOrNull { it.name == savedInstanceState?.getString("pending_bluetooth") }
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
        pageScroll = findViewById(R.id.page_scroll)
        progress = findViewById(R.id.recognition_progress)
        retryButton = findViewById(R.id.retry_gallery)
        cancelButton = findViewById(R.id.cancel_gallery)
        retryButton.setOnClickListener { app.retryGallery() }
        cancelButton.setOnClickListener {
            app.cancelGallery()
            message("gallery_cancelled")
        }
        logToggle.setOnClickListener {
            logOpen = !logOpen
            logScroll.visibility = if (logOpen) View.VISIBLE else View.GONE
            logToggle.text = Lang.s(app.lang, if (logOpen) "log_hide" else "log")
        }

        deviceBox = findViewById(R.id.devices)
        scanButton = findViewById(R.id.scan)
        scanButton.setOnClickListener {
            pendingBluetooth = if (app.link.savedMac == null) BluetoothAction.SEARCH else BluetoothAction.CONNECT
            continueBluetoothAction()
        }
        otherButton = findViewById(R.id.connect_other)
        otherButton.setOnClickListener {
            pendingBluetooth = BluetoothAction.SEARCH
            continueBluetoothAction()
        }
        galleryButton = findViewById(R.id.gallery)
        galleryButton.setOnClickListener { pickPhoto() }
        findViewById<Button>(R.id.phone_camera).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.text_search).setOnClickListener { showCatalog() }

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
            when (app.saveQwenKey(typed)) {
                KeyStorage.Result.SAVED -> {
                    keyInput.text.clear()
                    refresh()
                }
                KeyStorage.Result.INVALID -> keyInput.error = Lang.s(app.lang, "key_invalid")
                KeyStorage.Result.FAILED -> keyInput.error = Lang.s(app.lang, "key_save_failed")
            }
        }

        // Gallery-only use needs no Bluetooth, location, or notification permission prompt.
        if (app.link.savedMac != null && hasBluetoothPermissions()) startLinkService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingBluetooth?.let { outState.putString("pending_bluetooth", it.name) }
        pendingCapture?.let { outState.putString("camera_file", it.name) }
        super.onSaveInstanceState(outState)
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
        findViewById<Button>(R.id.phone_camera).text = if (Lang.zh(lang)) "拍照识别" else "Take a photo"
        findViewById<Button>(R.id.text_search).text = if (Lang.zh(lang)) "文字查询（免费）" else "Text lookup (free)"
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
        cityHint.text = Lang.s(lang, hintKey(app.city)) + "\n" + if (app.serviceCovered()) app.housing().label(lang)
            else if (Lang.zh(lang)) "服务范围待确认，请打开设置" else "Confirm service area in Settings"
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
        val processing = app.busyFor(app.city)
        val answerQuestion = findViewById<Button>(R.id.answer_question)
        val question = a?.let { Clarification.next(it) }
        answerQuestion.visibility = if (!processing && question != null) View.VISIBLE else View.GONE
        answerQuestion.text = if (Lang.zh(lang)) "补充一个信息" else "Answer a question"
        answerQuestion.setOnClickListener { a?.let { showQuestion(it) } }
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        progress.contentDescription = Lang.s(lang, "working")
        retryButton.text = Lang.s(lang, "retry_gallery")
        retryButton.visibility = if (app.canRetryGallery()) View.VISIBLE else View.GONE
        cancelButton.text = Lang.s(lang, "cancel_gallery")
        cancelButton.visibility = if (app.canCancelGallery()) View.VISIBLE else View.GONE
        if (processing && !wasProcessing) pageScroll.post {
            pageScroll.smoothScrollTo(0, (resultCard.top - dp(16)).coerceAtLeast(0))
        }
        wasProcessing = processing
        val cityRule = app.rules.cities.getValue(app.city)
        val provenance = findViewById<TextView>(R.id.rule_provenance)
        val applied = a?.itemId?.let { cityRule.rules[it]?.forEvidence(a.reply?.observation?.material, a.reply?.observation?.paper) }
        provenance.visibility = if (!processing && applied != null && app.serviceCovered()) View.VISIBLE else View.GONE
        provenance.text = if (Lang.zh(lang)) "规则来源：${cityRule.authority}\n规则版本：${cityRule.updated} · 核查：${applied?.checkedOn?.ifEmpty { "未记录" }}"
            else "Authority: ${cityRule.authority}\nTable: ${cityRule.updated} · Checked: ${applied?.checkedOn?.ifEmpty { "not recorded" }}"
        findViewById<Button>(R.id.profile_settings).apply {
            visibility = if (!app.serviceCovered()) View.VISIBLE else View.GONE
            text = if (Lang.zh(lang)) "确认住房和清运服务" else "Confirm housing and collection service"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val route = a?.takeIf { it.kind == com.baybin.protocol.Proto.KIND_OK && it.bin == "special" }
            ?.itemId?.let { cityRule.destinations[it] }
        findViewById<Button>(R.id.disposal_destination).apply {
            visibility = if (!processing && route != null) View.VISIBLE else View.GONE
            text = if (Lang.zh(lang)) "打开官方投放预约" else "Open official drop-off booking"
            setOnClickListener { route?.let { openPage(it.url) } }
        }
        findViewById<TextView>(R.id.disposal_note).apply {
            visibility = if (!processing && route != null) View.VISIBLE else View.GONE
            text = route?.let { if (Lang.zh(lang)) "${it.noteZh}\n入口核查：${it.checkedOn}" else "${it.noteEn}\nLink checked: ${it.checkedOn}" }.orEmpty()
        }
        if (processing) {
            resultLabel.text = Lang.s(lang, "result_label")
            line1.text = Lang.s(lang, "working")
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            line2.text = Lang.s(lang, "working_hint")
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
            resultLabel.text = Lang.s(lang, if (a.kind == com.baybin.protocol.Proto.KIND_OK) "throw" else "result_label")
            line1.text = face.title
            line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (face.title.length <= 3) 44f else 28f)
            val reason = Lang.reason(a.line2, lang)
            line2.text = when {
                face.note != null && reason.isNotBlank() -> face.note + "\n" + reason
                face.note != null -> face.note
                else -> reason
            }
            val name = a.itemId?.let { id -> app.rules.item(id)?.name?.let { Lang.itemName(id, it, lang) } }
            val source = when (val src = app.sourceFor(app.city)) {
                "glasses" -> Lang.s(lang, "source_glasses")
                "gallery" -> Lang.s(lang, "source_gallery")
                "camera" -> if (Lang.zh(lang)) "手机拍照" else "Phone camera"
                "lookup" -> if (Lang.zh(lang)) "文字查询" else "Text lookup"
                else -> src.takeIf { it.isNotEmpty() }
            }
            val evidence = if (a.reply?.userFields?.isNotEmpty() == true) {
                if (Lang.zh(lang)) "含你补充的信息" else "Includes your answers"
            } else if (a.reply?.userIdentified == true) {
                if (Lang.zh(lang)) "按你选择的物品" else "Based on your item selection"
            } else null
            meta.text = listOfNotNull(source, name, evidence).joinToString("  ·  ")
            showPage(a.sourceUrl ?: app.rules.cities[app.city]?.guideUrl)
            paint(face.background, face.onLight)
        }
        val bmp = app.photoFor(app.city)
        if (bmp == null || processing) photo.visibility = View.GONE
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
        if (!readyForPhoto()) return
        app.pipeline.warmUp() // the cloud connections are ready by the time a photo is picked
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        }
        intent.type = "image/*"
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            message("gallery_closed")
        }
    }

    @Deprecated("Activity result API needs AndroidX; this app has none")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA) {
            val file = pendingCapture ?: return
            pendingCapture = null
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", file)
            revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (resultCode == RESULT_OK && file.length() > 0) app.classifyCapture(file)
            else {
                file.delete()
                if (resultCode == RESULT_OK) message("camera_failed")
            }
            return
        }
        if (requestCode == REQ_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) continueBluetoothAction()
            else {
                pendingBluetooth = null
                message("bluetooth_off")
            }
            return
        }
        if (requestCode == REQ_APP_SETTINGS) {
            if (hasBluetoothPermissions()) continueBluetoothAction()
            else {
                pendingBluetooth = null
                message("no_bt")
            }
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        // Samsung's photo picker often leaves data null and puts the uri in clipData only.
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            message("gallery_empty")
            return
        }
        app.classifyUri(uri)
    }

    // ---- permissions ----

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions() = BluetoothAccess.canUse(Build.VERSION.SDK_INT, ::granted)

    @SuppressLint("MissingPermission")
    private fun continueBluetoothAction() {
        val action = pendingBluetooth ?: return
        val needed = BluetoothAccess.request(Build.VERSION.SDK_INT, ::granted)
        if (needed.isNotEmpty()) {
            val asked = getPreferences(MODE_PRIVATE).getBoolean("bluetooth_permissions_requested", false)
            val blocked = BluetoothAccess.required(Build.VERSION.SDK_INT).any {
                !granted(it) && !shouldShowRequestPermissionRationale(it)
            }
            if (asked && blocked) {
                AlertDialog.Builder(this)
                    .setMessage(Lang.s(app.lang, "permissions_help"))
                    .setPositiveButton(Lang.s(app.lang, "open_settings")) { _, _ ->
                        startActivityForResult(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")), REQ_APP_SETTINGS)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> pendingBluetooth = null }
                    .setOnCancelListener { pendingBluetooth = null }
                    .show()
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("bluetooth_permissions_requested", true).apply()
                requestPermissions(needed.toTypedArray(), REQ_PERMS)
            }
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                pendingBluetooth = null
                message("bluetooth_unavailable")
                return
            }
            if (!adapter.isEnabled) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BLUETOOTH)
                return
            }
            pendingBluetooth = null
            startLinkService()
            val mac = app.link.savedMac
            if (action == BluetoothAction.SEARCH || mac == null) beginScan()
            else app.link.connect(mac)
        } catch (_: SecurityException) {
            pendingBluetooth = null
            message("no_bt")
        } catch (_: ActivityNotFoundException) {
            pendingBluetooth = null
            message("bluetooth_unavailable")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasBluetoothPermissions()) continueBluetoothAction()
        else {
            pendingBluetooth = null
            message("no_bt")
        }
    }

    private fun message(key: String) {
        val text = Lang.s(app.lang, key)
        EventLog.add(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun showQuestion(previous: Pipeline.Answer) {
        val question = Clarification.next(previous) ?: return
        AlertDialog.Builder(this).setTitle(question.title(app.lang))
            .setItems(question.choices.map { it.label(app.lang) }.toTypedArray()) { _, index ->
                app.clarify(previous, question.choices[index].value)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showCatalog() {
        val zh = Lang.zh(app.lang)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val input = EditText(this).apply {
            hint = if (zh) "物品名称，如外卖盒、电池" else "Item name, e.g. takeout box or battery"
            setSingleLine(true)
        }
        box.addView(input)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val list = ListView(this).apply { this.adapter = adapter }
        box.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        val empty = TextView(this).apply { text = if (zh) "没有匹配的物品，请换个词或查看官方指南。" else "No matching item. Try another term or open the official guide." }
        box.addView(empty)
        list.emptyView = empty
        var matches = emptyList<Rules.Item>()
        fun filter() {
            matches = CatalogSearch.find(app.rules.items, input.text.toString())
            adapter.clear()
            adapter.addAll(matches.map { if (zh) "${Lang.itemName(it.id, it.name, app.lang)} · ${it.name}" else it.name })
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) {}
        })
        filter()
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (zh) "离线文字查询 · 请确认具体物品" else "Offline lookup · choose the exact item")
            .setView(box).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (zh) "官方指南" else "Official guide") { _, _ -> app.rules.cities[app.city]?.let { openPage(it.guideUrl) } }
            .create()
        list.setOnItemClickListener { _, _, index, _ ->
            val item = matches.getOrNull(index) ?: return@setOnItemClickListener
            dialog.dismiss()
            app.lookup(item.id)
            app.answerFor(app.city)?.let { showQuestion(it) }
        }
        dialog.show()
    }

    private fun readyForPhoto(): Boolean {
        if (!app.serviceCovered()) {
            Toast.makeText(this, if (Lang.zh(app.lang)) "请先确认住房类型和清运服务" else "Confirm housing and collection service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return false
        }
        if (app.hasQwenKey()) return true
        AlertDialog.Builder(this).setMessage(Lang.s(app.lang, "key_missing"))
            .setPositiveButton(Lang.s(app.lang, "settings")) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNeutralButton(if (Lang.zh(app.lang)) "先用文字查询" else "Use text lookup") { _, _ -> showCatalog() }
            .setNegativeButton(android.R.string.cancel, null).show()
        return false
    }

    private fun takePhoto() {
        if (pendingCapture != null || !readyForPhoto()) return
        var file: File? = null
        var captureUri: Uri? = null
        try {
            file = CaptureFiles.create(cacheDir)
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", file)
            captureUri = uri
            pendingCapture = file
            app.pipeline.warmUp()
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("BayBin capture", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, REQ_CAMERA)
        } catch (_: Exception) {
            captureUri?.let { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            file?.delete()
            pendingCapture = null
            message("camera_failed")
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
        private const val REQ_ENABLE_BLUETOOTH = 3
        private const val REQ_APP_SETTINGS = 4
        private const val REQ_CAMERA = 5
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
