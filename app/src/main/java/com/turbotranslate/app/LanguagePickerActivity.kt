package com.turbotranslate.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Full-screen "pick a language" list, filterable by typing digits —
 * T9-style: type the digits for the language's name, and any language
 * whose name starts with those digits (per-word, so "man" narrows to
 * "Mandarin Chinese") stays in the list.
 *
 * Selecting is center/OK only — no softkey or number-key alias, since
 * those digits are already spoken for by the search above. Opened from
 * TranslatorActivity's Options menu (`*` key). Selecting a language (or backing
 * out with nothing typed) both just finish() the activity — the result
 * is written straight to SettingsHelper.
 */
class LanguagePickerActivity : AppCompatActivity() {

    private lateinit var adapter: LanguagePickerAdapter
    private lateinit var searchQueryView: android.widget.TextView
    private lateinit var languageList: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager

    private val queryDigits = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_picker)

        searchQueryView = findViewById(R.id.searchQuery)
        languageList = findViewById(R.id.languageList)
        layoutManager = LinearLayoutManager(this)
        languageList.layoutManager = layoutManager

        adapter = LanguagePickerAdapter(LanguageList.ALL)
        languageList.adapter = adapter

        findViewById<android.widget.TextView>(R.id.pickerTitle).text =
            "Other language (currently ${SettingsHelper.getOtherLanguage(this)})"
    }

    private fun refilter() {
        val digits = queryDigits.toString()
        searchQueryView.text = digits
        val filtered = if (digits.isEmpty()) {
            LanguageList.ALL
        } else {
            LanguageList.ALL.filter { name ->
                name.split(Regex("[\\s()]+")).filter { it.isNotEmpty() }
                    .any { T9Digits.digitCodeFor(it).startsWith(digits) }
            }
        }
        adapter.update(filtered)
        languageList.scrollToPosition(0)
    }

    private fun moveSelection(delta: Int) {
        val newPos = adapter.getSelectedPosition() + delta
        if (newPos < 0 || newPos >= adapter.itemCount) return
        adapter.setSelectedPosition(newPos)
        languageList.scrollToPosition(newPos)
    }

    private fun confirmSelection() {
        val items = adapter.currentItems()
        if (items.isEmpty()) return
        val chosen = items[adapter.getSelectedPosition()]
        SettingsHelper.setOtherLanguage(this, chosen)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 -> {
                queryDigits.append(('0' + (event.keyCode - KeyEvent.KEYCODE_0)))
                refilter()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                confirmSelection()
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                if (queryDigits.isNotEmpty()) {
                    queryDigits.deleteCharAt(queryDigits.length - 1)
                    refilter()
                } else {
                    finish() // no change — TranslatorActivity keeps whatever was already set
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
