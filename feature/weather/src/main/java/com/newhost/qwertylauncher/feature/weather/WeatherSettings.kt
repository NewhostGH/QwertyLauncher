package com.newhost.qwertylauncher.feature.weather

import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting

internal object WeatherSettings {
    val units = ChoiceSetting(
        "units",
        "weather units",
        listOf(Choice("metric", "°C, km/h"), Choice("imperial", "°F, mph")),
        default = "metric",
    )
    val refreshMinutes = NumberSetting(
        "refresh_minutes",
        "refresh weather every",
        default = 30,
        min = 15,
        max = 180,
        step = 15,
        unit = "min",
    )

    val rainWarning = ToggleSetting(
        "rain_warning",
        "rain warning",
        default = true,
        summary = "Says on the tile and in the report when rain, snow or storms are likely in the next 12 hours, or until when it rains.",
    )
    val rainChance = NumberSetting(
        "rain_warning_chance",
        "rain warning from a chance of",
        default = 50,
        min = 20,
        max = 90,
        step = 10,
        unit = "%",
    )

    val all = arrayOf(units, refreshMinutes, rainWarning, rainChance)

    /** The rain warning for [outlook] as set up here, or null. */
    fun warning(settings: SettingsStore, outlook: Outlook, nowCode: Int): String? =
        if (settings[rainWarning]) outlook.warning(nowCode, settings[rainChance]) else null
}
