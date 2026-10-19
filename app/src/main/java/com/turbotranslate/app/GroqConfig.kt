package com.turbotranslate.app

import android.os.Environment
import java.io.File

/**
 * The Groq API key lives in the same plain text file TurboVoice and
 * TurboText read — Internal storage / Turbo Key / groq_api_key.txt — so
 * one key on the phone covers every Turbo app and nothing is baked into
 * the APK. Get one free at https://console.groq.com/keys.
 *
 * A GROQ_API_KEY line in local.properties still works as a build-time
 * fallback (BuildConfig.GROQ_API_KEY) for builds made without the file.
 */
object GroqConfig {
    private const val FOLDER_NAME = "Turbo Key"
    private const val FILE_NAME = "groq_api_key.txt"
    const val KEY_FILE_PATH = "Internal storage/$FOLDER_NAME/$FILE_NAME"

    fun keyFile(): File =
        File(File(Environment.getExternalStorageDirectory(), FOLDER_NAME), FILE_NAME)

    /** Creates the folder and an empty key file if neither TurboVoice nor
     *  TurboText has yet, so there's somewhere obvious to paste a key.
     *  No-op once the file exists; fails silently without storage access. */
    fun ensureKeyFileExists() {
        try {
            val file = keyFile()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        } catch (e: Exception) {
            // Storage access not granted yet — TranslatorActivity asks for it.
        }
    }

    /** The key file's contents, else the build-time key, else "". */
    fun getApiKey(): String {
        val fromFile = try {
            keyFile().readText().trim()
        } catch (e: Exception) {
            ""
        }
        return fromFile.ifEmpty { BuildConfig.GROQ_API_KEY.trim() }
    }
}
