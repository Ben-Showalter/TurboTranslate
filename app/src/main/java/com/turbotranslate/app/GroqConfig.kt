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
 * fallback (BuildConfig.GROQ_API_KEY), in debug builds only. It's used
 * only when the file can't be read, so a stale key there shows up as a
 * 401 naming "the built-in fallback".
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

    enum class KeySource(val label: String) {
        FILE("the Turbo Key file"),
        BUILT_IN("the built-in fallback (local.properties)"),
        NONE("nowhere"),
    }

    /** The key file's contents, else the build-time key, else "". */
    fun getApiKey(): String = resolve().first

    /** Where [getApiKey]'s key comes from, for error messages. */
    fun keySource(): KeySource = resolve().second

    private fun resolve(): Pair<String, KeySource> {
        val fromFile = try {
            clean(keyFile().readText())
        } catch (e: Exception) {
            "" // unreadable — usually storage access not granted
        }
        if (fromFile.isNotEmpty()) return fromFile to KeySource.FILE
        val builtIn = clean(BuildConfig.GROQ_API_KEY)
        if (builtIn.isNotEmpty()) return builtIn to KeySource.BUILT_IN
        return "" to KeySource.NONE
    }

    /** First non-empty line, minus a byte-order mark or zero-width
     *  characters (some editors add them, and trim() leaves them), quotes,
     *  and a pasted "GROQ_API_KEY=" prefix. */
    private fun clean(raw: String): String {
        val line = raw.replace(Regex("[\\uFEFF\\u200B-\\u200D\\u2060]"), "")
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line.removePrefix("GROQ_API_KEY=").trim().trim('"', '\'').trim()
    }

    /** One wording for "no key" across the voice, translate and photo paths. */
    fun missingKeyMessage(): String = "No Groq API key — paste it into $KEY_FILE_PATH"

    /** Toast text for a 401: says which key was sent, by its last 4
     *  characters, so it can be checked against the key file. */
    fun rejectedKeyMessage(): String {
        val (key, source) = resolve()
        return "Groq rejected the API key (401). Using the key from ${source.label}, " +
            "ending …${key.takeLast(4)}"
    }
}
