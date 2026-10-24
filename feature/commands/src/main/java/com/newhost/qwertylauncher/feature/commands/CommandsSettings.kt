package com.newhost.qwertylauncher.feature.commands

import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.ToggleSetting

internal object CommandsSettings {
    /** Choice value = search URL prefix; the query is appended URL-encoded. */
    val searchEngine = ChoiceSetting(
        "search_engine",
        "web search engine",
        listOf(
            Choice("https://duckduckgo.com/?q=", "duckduckgo"),
            Choice("https://www.google.com/search?q=", "google"),
            Choice("https://www.startpage.com/do/search?q=", "startpage"),
            Choice("https://search.brave.com/search?q=", "brave"),
            Choice("https://www.bing.com/search?q=", "bing"),
            Choice("https://kagi.com/search?q=", "kagi"),
        ),
        default = "https://duckduckgo.com/?q=",
    )
    val webFallback = ToggleSetting("web_fallback", "offer a web search for anything typed", default = true)
    val liveCalc = ToggleSetting("live_calc", "show results as you type maths", default = true)
    val degrees = ToggleSetting(
        "calc_degrees",
        "calculator angles in degrees",
        default = true,
        summary = "For sin cos tan and their inverses. Off means radians.",
    )

    val all = arrayOf(searchEngine, webFallback, liveCalc, degrees)
}
