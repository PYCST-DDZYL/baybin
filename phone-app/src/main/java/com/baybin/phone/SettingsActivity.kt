package com.baybin.phone

import android.app.Activity
import android.os.Bundle
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
        val back = findViewById<TextView>(R.id.settings_back)
        val title = findViewById<TextView>(R.id.settings_title)
        val note = findViewById<TextView>(R.id.settings_note)
        val input = findViewById<EditText>(R.id.key_input)
        val save = findViewById<Button>(R.id.key_save)
        val state = findViewById<TextView>(R.id.key_state)
        val lang = app.lang
        back.text = Lang.s(lang, "settings_back")
        title.text = Lang.s(lang, "settings")
        note.text = Lang.s(lang, "settings_note")
        input.hint = Lang.s(lang, if (app.hasQwenKey()) "key_replace" else "key_hint")
        save.text = Lang.s(lang, "key_save")
        state.text = Lang.s(lang, if (app.hasQwenKey()) "key_set" else "key_missing")
        back.setOnClickListener { finish() }
        save.setOnClickListener {
            val typed = input.text?.toString()?.trim().orEmpty()
            if (typed.isEmpty()) return@setOnClickListener
            app.saveQwenKey(typed)
            input.text.clear()
            finish()
        }
    }
}
