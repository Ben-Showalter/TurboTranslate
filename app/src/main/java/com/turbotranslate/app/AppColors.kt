package com.turbotranslate.app

/** Purple accent theme (matching TurboTranslate branding — the other
 *  Turbo apps each have their own accent color, e.g. TurboWeather's
 *  orange). Hardcoded here rather than pulled in as a whole switchable
 *  theme system, since this standalone app doesn't need one. */
object AppColors {
    const val BACKGROUND = 0xFF000000.toInt()
    const val SURFACE = 0xFF221A2E.toInt()
    const val SURFACE_2 = 0xFF3B2A54.toInt()
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFF888888.toInt()
    const val TEXT_ORIGINAL = 0xFFD0D0D0.toInt()
    const val TEXT_TERTIARY = 0xFFAAAAAA.toInt()
    const val ACCENT = 0xFFBB86FC.toInt()
    const val ACCENT_LIGHT = 0xFFD8BBFF.toInt()
    const val BUBBLE_INCOMING = 0xFF333333.toInt()
    const val BUBBLE_OUTGOING = 0xFF4A2472.toInt()
    const val BUBBLE_TEXT = 0xFFFFFFFF.toInt()
}
