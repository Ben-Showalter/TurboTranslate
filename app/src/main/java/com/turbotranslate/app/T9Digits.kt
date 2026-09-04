package com.turbotranslate.app

/** Standalone T9 digit-code mapping — this is the same technique
 *  FlipText's contact search uses (see T9Engine.digitCodeFor there), but
 *  reimplemented as a plain function instead of pulling in the whole
 *  T9Engine class. T9Engine's init block loads a 103,000-word dictionary
 *  asset FlipText ships for predictive text, which this app has no use
 *  for and doesn't include — LanguagePickerActivity only ever needs to
 *  turn a language name into its digit code to filter a fixed list. */
object T9Digits {
    private val letterToDigit: Map<Char, Char> = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    fun digitCodeFor(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            letterToDigit[ch]?.let { sb.append(it) }
        }
        return sb.toString()
    }
}
