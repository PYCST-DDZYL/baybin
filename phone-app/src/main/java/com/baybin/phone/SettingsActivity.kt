package com.baybin.phone

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Replace the Qwen key. The glasses never see it; the phone is what calls the model.
 * The saved key is not shown back.
 */
class SettingsActivity : Activity() {
    private var hasServiceDraft: () -> Boolean = { false }
    private var advancedOpen = false
    private var discardDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        val app = application as App
        hasServiceDraft = ServiceSettings.bind(this, app)
        val back = findViewById<TextView>(R.id.settings_back)
        val title = findViewById<TextView>(R.id.settings_title)
        val note = findViewById<TextView>(R.id.settings_note)
        val input = findViewById<EditText>(R.id.key_input)
        val save = findViewById<Button>(R.id.key_save)
        val state = findViewById<TextView>(R.id.key_state)
        val advanced = findViewById<TextView>(R.id.model_toggle)
        val modelBlock = findViewById<View>(R.id.model_block)
        advancedOpen = savedInstanceState?.getBoolean("advanced_open") ?: false
        modelBlock.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        val modelInput = findViewById<EditText>(R.id.model_input)
        val modelSave = findViewById<Button>(R.id.model_save)
        val modelNote = findViewById<TextView>(R.id.model_note)
        val lang = app.lang
        back.text = Lang.s(lang, "settings_back")
        title.text = Lang.s(lang, "settings")
        note.text = Lang.s(lang, "settings_note")
        input.hint = Lang.s(lang, if (app.hasQwenKey()) "key_replace" else "key_hint")
        save.text = Lang.s(lang, "key_save")
        state.text = Lang.s(lang, if (app.hasQwenKey()) "key_set" else "key_missing")
        advanced.text = Lang.s(lang, "model_toggle")
        modelInput.setText(app.qwenModel)
        modelNote.text = Lang.s(lang, "model_note")
        modelSave.text = Lang.s(lang, "model_save")
        advanced.setOnClickListener {
            advancedOpen = !advancedOpen
            modelBlock.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        }
        modelSave.setOnClickListener {
            if (app.saveQwenModel(modelInput.text.toString())) {
                modelInput.error = null
                modelInput.setText(app.qwenModel)
                Toast.makeText(this, Lang.s(lang, "model_saved"), Toast.LENGTH_SHORT).show()
            }
            else modelInput.error = Lang.s(lang, "model_invalid")
        }
        back.setOnClickListener { onBackPressed() }
        save.setOnClickListener {
            val typed = input.text?.toString()?.trim().orEmpty()
            when (app.saveQwenKey(typed)) {
                KeyStorage.Result.SAVED -> {
                    input.text.clear()
                    input.error = null
                    input.hint = Lang.s(lang, "key_replace")
                    state.text = Lang.s(lang, "key_set")
                    Toast.makeText(this, Lang.s(lang, "key_saved"), Toast.LENGTH_SHORT).show()
                }
                KeyStorage.Result.INVALID -> input.error = Lang.s(lang, "key_invalid")
                KeyStorage.Result.FAILED -> input.error = Lang.s(lang, "key_save_failed")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("advanced_open", advancedOpen)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Native Activity back callback for the current target SDK")
    override fun onBackPressed() {
        val app = application as App
        val key = findViewById<EditText>(R.id.key_input).text.toString()
        val model = findViewById<EditText>(R.id.model_input).text.toString().trim()
        if (!hasServiceDraft() && key.isBlank() && model == app.qwenModel) {
            finish()
            return
        }
        if (discardDialog?.isShowing == true) return
        val zh = Lang.zh(app.lang)
        discardDialog = AlertDialog.Builder(this)
            .setTitle(if (zh) "还有修改未保存" else "Unsaved changes")
            .setMessage(if (zh) "继续编辑，或放弃未保存的修改后返回。" else "Keep editing, or discard unsaved changes and go back.")
            .setPositiveButton(if (zh) "继续编辑" else "Keep editing", null)
            .setNegativeButton(if (zh) "放弃修改" else "Discard changes") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        discardDialog?.dismiss()
        super.onDestroy()
    }
}
