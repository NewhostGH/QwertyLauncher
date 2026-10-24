package com.newhost.qwertylauncher.feature.clock

import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice

internal object ClockSettings {
    val clockStyle = ChoiceSetting(
        "clock_style",
        "clock style",
        listOf(Choice("art", "ascii art"), Choice("segments", "7-segment")),
        default = "art",
    )
    val hourFormat = ChoiceSetting(
        "hour_format",
        "12 or 24 hour clock",
        listOf(Choice("system"), Choice("24h"), Choice("12h")),
        default = "system",
    )

    val all = arrayOf(clockStyle, hourFormat)
}
