package com.newhost.qwertylauncher.settings

import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The launcher's settings as a file (`config export` writes one, `config
 * import` reads one): config's values by storage key, and the few
 * preference files that hold settings, by name. JSON, so it can be read and
 * edited by hand.
 */
class SettingsFile(
    /** The launcher version that wrote it. */
    val app: String,
    /** The day it was written, as yyyy-mm-dd. */
    val exported: String,
    /** Config's values: storage key ("module.key") to a [Boolean], [Int], [Long] or [String]. */
    val settings: Map<String, Any>,
    /** Preference file name to its values, of the same types. */
    val prefs: Map<String, Map<String, Any>>,
) {
    fun toJson(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("app", app)
        .put("exported", exported)
        .put("settings", objectOf(settings))
        .put("prefs", JSONObject().apply { prefs.toSortedMap().forEach { (name, values) -> put(name, objectOf(values)) } })
        .toString(2)

    companion object {
        const val FORMAT = "qwertylauncher-settings"
        const val VERSION = 1

        /** The kinds of value settings are stored as. */
        fun isStorable(value: Any?) = value is Boolean || value is Int || value is Long || value is String

        /** Reads a file written by [toJson]; throws [IOException] saying, in plain words, what's wrong with it. */
        fun parse(text: String): SettingsFile {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("it isn't a settings file (not JSON)", e)
            }
            if (json.optString("format") != FORMAT) throw IOException("it isn't a QwertyLauncher settings file")
            val version = json.optInt("version", 0)
            if (version > VERSION) throw IOException("it was written by a newer launcher; update this one first")
            val prefs = json.optJSONObject("prefs")
            return SettingsFile(
                app = json.optString("app"),
                exported = json.optString("exported"),
                settings = valuesOf(json.optJSONObject("settings")),
                prefs = prefs?.keys()?.asSequence()?.associateWith { valuesOf(prefs.optJSONObject(it)) }.orEmpty(),
            )
        }

        private fun objectOf(values: Map<String, Any>) =
            JSONObject().apply { values.toSortedMap().forEach { (key, value) -> put(key, value) } }

        /** A JSON object's values that settings can hold; anything else (lists, decimals, null) is left out. */
        private fun valuesOf(json: JSONObject?): Map<String, Any> {
            json ?: return emptyMap()
            return json.keys().asSequence().mapNotNull { key ->
                val value = json.get(key)
                (key to value).takeIf { isStorable(value) }
            }.toMap()
        }
    }
}
