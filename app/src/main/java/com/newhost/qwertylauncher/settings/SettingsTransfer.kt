package com.newhost.qwertylauncher.settings

import android.content.Context
import android.content.pm.PackageManager
import java.time.LocalDate

/**
 * Settings export and import: the same settings Android's backup keeps
 * (see res/xml/data_extraction_rules.xml), as a [SettingsFile] the user
 * keeps wherever they like. Not AI keys: this phone's keystore seals them.
 */
class SettingsTransfer(private val context: Context, private val settings: DataStoreSettings) {

    fun export(): SettingsFile = SettingsFile(
        app = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty(),
        exported = LocalDate.now().toString(),
        settings = settings.storedValues(),
        prefs = PREFS.keys.associateWith { name -> stored(name) },
    )

    /** How many of [file]'s values are the wrong kind for their setting, and will be left out. */
    fun misfits(file: SettingsFile): Int = file.settings.size - settings.fitting(file.settings).size

    /**
     * What importing [file] would change, in words ("7 settings and the tile
     * order"); null if it matches what's set now.
     */
    fun changes(file: SettingsFile): String? {
        val current = settings.storedValues()
        val incoming = settings.fitting(file.settings)
        val changed = (current.keys + incoming.keys).count { current[it] != incoming[it] }
        val parts = listOfNotNull(
            changed.takeIf { it > 0 }?.let { if (it == 1) "1 setting" else "$it settings" },
        ) + PREFS.filter { (name, _) -> (file.prefs[name] ?: emptyMap()) != stored(name) }.values
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    /**
     * Replaces the settings with [file]'s, leaving out the [misfits]. Restart
     * afterwards: the launcher reads some of them only as it starts.
     */
    suspend fun apply(file: SettingsFile) {
        for (name in PREFS.keys) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            file.prefs[name].orEmpty().forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            // At once, not in the background: a restart follows.
            editor.commit()
        }
        settings.replaceAll(file.settings)
    }

    private fun stored(name: String): Map<String, Any> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> value?.takeIf { SettingsFile.isStorable(it) }?.let { key to it } }
            .toMap()

    private companion object {
        /**
         * The preference files that hold settings, and what they are in words.
         * Keep in step with data_extraction_rules.xml.
         */
        val PREFS = linkedMapOf(
            "tiles" to "the tile order",
            "top_bar" to "the top bar",
            "ai" to "the AI's models and addresses",
        )
    }
}
