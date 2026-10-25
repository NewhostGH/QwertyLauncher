package com.newhost.qwertylauncher.settings

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFileTest {

    private val file = SettingsFile(
        app = "1.0",
        exported = "2026-10-24",
        settings = mapOf("general.font" to "plex", "weather.enabled" to false, "layout.clock.width" to 12),
        prefs = mapOf("tiles" to mapOf("order" to "clock,weather"), "ai" to emptyMap()),
    )

    @Test
    fun roundTrips() {
        val back = SettingsFile.parse(file.toJson())
        assertEquals("1.0", back.app)
        assertEquals("2026-10-24", back.exported)
        assertEquals(file.settings, back.settings)
        assertEquals(file.prefs, back.prefs)
    }

    @Test
    fun keepsBigNumbersAsLongs() {
        val back = SettingsFile.parse(file.copyWith(mapOf("x.seen" to 5_000_000_000L)).toJson())
        assertEquals(5_000_000_000L, back.settings["x.seen"])
    }

    @Test
    fun leavesOutValuesNoSettingCanHold() {
        val text = """{"format":"qwertylauncher-settings","version":1,
            "settings":{"a.ok":true,"a.list":[1,2],"a.half":0.5,"a.none":null,"a.obj":{}}}"""
        assertEquals(mapOf("a.ok" to true), SettingsFile.parse(text).settings)
    }

    @Test
    fun refusesOtherFiles() {
        assertTrue(message { SettingsFile.parse("not json") }.contains("not JSON"))
        assertTrue(message { SettingsFile.parse("""{"format":"something-else"}""") }.contains("isn't a QwertyLauncher"))
        assertTrue(message { SettingsFile.parse("""{"format":"qwertylauncher-settings","version":99}""") }.contains("newer"))
    }

    @Test
    fun aFileWithoutSectionsIsEmpty() {
        val empty = SettingsFile.parse("""{"format":"qwertylauncher-settings","version":1}""")
        assertTrue(empty.settings.isEmpty())
        assertTrue(empty.prefs.isEmpty())
    }

    private fun SettingsFile.copyWith(settings: Map<String, Any>) = SettingsFile(app, exported, settings, prefs)

    private fun message(block: () -> Unit): String = assertThrows(IOException::class.java) { block() }.message.orEmpty()
}
