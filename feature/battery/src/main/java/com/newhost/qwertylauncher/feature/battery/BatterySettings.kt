package com.newhost.qwertylauncher.feature.battery

import com.newhost.qwertylauncher.core.ToggleSetting

internal object BatterySettings {
    val batteryTemperature = ToggleSetting("battery_temperature", "show battery temperature", default = true)

    val all = arrayOf(batteryTemperature)
}
