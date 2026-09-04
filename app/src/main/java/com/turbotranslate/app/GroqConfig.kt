package com.turbotranslate.app

/**
 * The Groq API key is no longer pasted in here directly — it's pulled in
 * at build time from local.properties (or a shared "turbo key folder",
 * if TURBO_KEY_FOLDER is set — see app/build.gradle for the exact lookup
 * logic and file format it currently expects) so it's never committed if
 * this project's source gets published, same as TurboText/TurboVoice.
 *
 * To set it: open local.properties (next to settings.gradle, alongside
 * sdk.dir) and add a line:
 *   GROQ_API_KEY=gsk_your_actual_key_here
 * Get a free key at https://console.groq.com/keys.
 */
object GroqConfig {
    const val API_KEY = BuildConfig.GROQ_API_KEY
}
