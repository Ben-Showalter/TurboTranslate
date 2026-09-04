package com.turbotranslate.app

/** Plain language names (not codes) — fed straight into
 *  GroqTranslateHelper's prompt, so no separate code-mapping table is
 *  needed. Roughly ordered by number of speakers worldwide, which matters
 *  here since it's also the fallback order shown before any digits are
 *  typed in LanguagePickerActivity. */
object LanguageList {
    val ALL: List<String> = listOf(
        "Spanish", "Mandarin Chinese", "Hindi", "Arabic", "French",
        "Bengali", "Portuguese", "Russian", "Urdu", "Indonesian",
        "German", "Japanese", "Swahili", "Marathi", "Telugu",
        "Turkish", "Tamil", "Vietnamese", "Korean", "Italian",
        "Punjabi", "Gujarati", "Persian", "Polish", "Ukrainian",
        "Malayalam", "Kannada", "Thai", "Burmese", "Amharic",
        "Somali", "Cantonese", "Tagalog", "Cebuano", "Haitian Creole",
        "Nepali", "Sinhala", "Khmer", "Lao", "Mongolian",
        "Hausa", "Yoruba", "Igbo", "Zulu", "Xhosa",
        "Afrikaans", "Dutch", "Greek", "Hebrew", "Hungarian",
        "Czech", "Slovak", "Romanian", "Bulgarian", "Serbian",
        "Croatian", "Bosnian", "Slovenian", "Macedonian", "Albanian",
        "Lithuanian", "Latvian", "Estonian", "Finnish", "Swedish",
        "Norwegian", "Danish", "Icelandic", "Armenian", "Georgian",
        "Azerbaijani", "Kazakh", "Uzbek", "Tajik", "Kyrgyz",
        "Pashto", "Kurdish", "Tibetan", "Malay", "Javanese",
        "Sundanese", "Fijian", "Samoan", "Tongan", "Maori",
        "Welsh", "Irish", "Scottish Gaelic", "Basque", "Catalan",
        "Maltese", "Yiddish", "Chichewa", "Shona", "Sesotho"
    )
}
