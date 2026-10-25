package com.newhost.qwertylauncher.feature.asciiicons

import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.ToggleSetting

internal object AsciiSettings {
    val colored = ToggleSetting("colored", "ascii art in colour", default = true, summary = "off: terminal green only")
    val charset = ChoiceSetting(
        "charset",
        "ascii art characters",
        listOf(Choice("classic"), Choice("dense"), Choice("blocks")),
        default = "classic",
        summary = "blocks needs a font with block glyphs",
    )
    val brightness = NumberSetting(
        "min_brightness",
        "ascii art minimum brightness",
        default = 80,
        min = 40,
        max = 100,
        step = 10,
        unit = "%",
        summary = "lifts dark icon colours off the black background",
    )

    // Animations: every frame is plain text, redrawn in place.
    val animateDrawIn = ToggleSetting(
        "animate_draw_in",
        "animate: art draws itself in",
        default = true,
        summary = "Art types itself in, a few lines at a time, when it first appears.",
    )
    val animateRoll = ToggleSetting(
        "animate_roll",
        "animate: art rolls over when it changes",
        default = true,
        summary = "Changed characters scramble, then settle.",
    )
    val animateLive = ToggleSetting(
        "animate_live",
        "animate: moving art (weather, bell)",
        default = true,
        summary = "Weather, bell and battery icons move.",
    )
    val animateShimmer = ToggleSetting(
        "animate_shimmer",
        "animate: selected art flickers",
        default = true,
        summary = "The art of whatever is selected flickers gently, like an old screen.",
    )
    val animateDecode = ToggleSetting(
        "animate_decode",
        "animate: boot banner decodes",
        default = true,
        summary = "The login banner decodes itself out of random characters.",
    )

    val all = arrayOf(colored, charset, brightness, animateDrawIn, animateRoll, animateLive, animateShimmer, animateDecode)

    /** Characters from empty to full. */
    fun ramp(charset: String): String = when (charset) {
        "dense" -> " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"
        "blocks" -> " ░▒▓█"
        else -> " .:-=+*#%@"
    }
}
