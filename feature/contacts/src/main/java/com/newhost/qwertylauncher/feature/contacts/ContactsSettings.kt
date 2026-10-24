package com.newhost.qwertylauncher.feature.contacts

import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.ToggleSetting

internal object ContactsSettings {
    val inSearch = ToggleSetting(
        "in_search",
        "contacts in search results",
        default = true,
        summary = "call and sms work either way",
    )
    val minLetters = NumberSetting("min_letters", "search contacts after", default = 2, min = 1, max = 5, unit = "letters")

    val all = arrayOf(inSearch, minLetters)
}
