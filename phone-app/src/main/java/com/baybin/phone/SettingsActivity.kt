package com.baybin.phone

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Replace the Qwen key. The glasses never see it; the phone is what calls the model.
 * The saved key is not shown back.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        val app = application as App
        ServiceSettings.bind(this, app)
        val back = findViewById<TextView>(R.id.settings_back)
        val title = findViewById<TextView>(R.id.settings_title)
        val note = findViewById<TextView>(R.id.settings_note)
        val input = findViewById<EditText>(R.id.key_input)
        val save = findViewById<Button>(R.id.key_save)
        val state = findViewById<TextView>(R.id.key_state)
        val advanced = findViewById<TextView>(R.id.model_toggle)
        val modelBlock = findViewById<View>(R.id.model_block)
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
            modelBlock.visibility = if (modelBlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        modelSave.setOnClickListener {
            if (app.saveQwenModel(modelInput.text.toString())) finish()
            else modelInput.error = Lang.s(lang, "model_invalid")
        }
        back.setOnClickListener { finish() }
        save.setOnClickListener {
            val typed = input.text?.toString()?.trim().orEmpty()
            when (app.saveQwenKey(typed)) {
                KeyStorage.Result.SAVED -> {
                    input.text.clear()
                    finish()
                }
                KeyStorage.Result.INVALID -> input.error = Lang.s(lang, "key_invalid")
                KeyStorage.Result.FAILED -> input.error = Lang.s(lang, "key_save_failed")
            }
        }
    }
}
