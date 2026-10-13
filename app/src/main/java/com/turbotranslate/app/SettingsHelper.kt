package com.turbotranslate.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object SettingsHelper {
    private const val PREFS = "flip_translate_settings"
    private const val TAG = "TurboTranslateSettings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The non-English side of the conversation — set via the searchable
     *  picker (LanguagePickerActivity), opened with the `*` key from
     *  TranslatorActivity. Defaults to Spanish. */
    fun getOtherLanguage(context: Context): String =
        prefs(context).getString("other_language", "Spanish") ?: "Spanish"

    fun setOtherLanguage(context: Context, language: String) {
        prefs(context).edit().putString("other_language", language).apply()
    }

    /** When TranslatorActivity last started an automatic update check
     *  (epoch ms, 0 = never) — see UpdateChecker. */
    fun getLastUpdateCheckAt(context: Context): Long =
        prefs(context).getLong("last_update_check_at", 0L)

    fun setLastUpdateCheckAt(context: Context, time: Long) {
        prefs(context).edit().putLong("last_update_check_at", time).apply()
    }

    /** Persists the transcript across app restarts — called after every
     *  new entry so a force-close never loses anything. Only the last
     *  MAX_TRANSCRIPT_ENTRIES are ever saved (the adapter itself already
     *  enforces that cap, but this trims defensively too). */
    fun saveTranscript(context: Context, entries: List<TranslatorEntry>) {
        val trimmed = entries.takeLast(MAX_TRANSCRIPT_ENTRIES)
        val arr = JSONArray()
        for (e in trimmed) {
            arr.put(JSONObject().apply {
                put("fromEnglish", e.fromEnglish)
                put("originalText", e.originalText)
                put("translatedText", e.translatedText)
                put("otherLanguageName", e.otherLanguageName)
                put("isPhoto", e.isPhoto)
            })
        }
        prefs(context).edit().putString("transcript_json", arr.toString()).apply()
    }

    fun loadTranscript(context: Context): List<TranslatorEntry> {
        val json = prefs(context).getString("transcript_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TranslatorEntry(
                    fromEnglish = o.getBoolean("fromEnglish"),
                    originalText = o.getString("originalText"),
                    translatedText = o.getString("translatedText"),
                    otherLanguageName = o.getString("otherLanguageName"),
                    isPhoto = o.optBoolean("isPhoto", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "couldn't parse saved transcript", e)
            emptyList()
        }
    }
}
