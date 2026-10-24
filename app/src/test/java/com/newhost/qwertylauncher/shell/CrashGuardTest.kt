package com.newhost.qwertylauncher.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var time = 1_000_000L
    private fun guard() = CrashGuard(folder.root, now = { time })

    @Test
    fun noCrashesLoadsEverything() {
        assertEquals(CrashGuard.Mode.ALL to emptyList<String>(), guard().start())
    }

    @Test
    fun oneCrashIsForgiven() {
        guard().record("weather")
        assertEquals(CrashGuard.Mode.ALL to emptyList<String>(), guard().start())
        assertTrue(guard().held.isEmpty())
    }

    @Test
    fun aModuleCrashingTwiceIsHeldBack() {
        guard().record("weather")
        time += 10_000
        guard().record("weather")
        assertEquals(CrashGuard.Mode.ALL to listOf("weather"), guard().start())
        assertEquals(setOf("weather"), guard().held)
        // Its crashes are dealt with: the next start doesn't step down because of them.
        assertEquals(CrashGuard.Mode.ALL to emptyList<String>(), guard().start())
    }

    @Test
    fun crashesLongAgoDontCount() {
        guard().record("weather")
        time += CrashGuard.WINDOW_MS + 1
        guard().record("weather")
        assertEquals(CrashGuard.Mode.ALL to emptyList<String>(), guard().start())
    }

    @Test
    fun unblamedCrashesStepDown() {
        guard().record(null)
        guard().record(null)
        assertEquals(CrashGuard.Mode.NO_IMPORTED, guard().start().first)
        guard().record(null)
        assertEquals(CrashGuard.Mode.NO_MODULES, guard().start().first)
        time += CrashGuard.WINDOW_MS + 1
        assertEquals(CrashGuard.Mode.ALL, guard().start().first)
    }

    @Test
    fun releaseLetsItLoadAgain() {
        guard().record("news")
        guard().record("news")
        guard().start()
        assertTrue(guard().release("news"))
        assertTrue(guard().held.isEmpty())
        assertEquals(false, guard().release("news"))
    }

    private fun thrownIn(className: String, cause: Throwable? = null) = RuntimeException("boom", cause).apply {
        stackTrace = arrayOf(
            StackTraceElement("android.view.View", "draw", null, 1),
            StackTraceElement(className, "render", null, 2),
            StackTraceElement("com.newhost.qwertylauncher.home.HomeActivity", "onCreate", null, 3),
        )
    }

    private val packages = mapOf(
        "com.newhost.qwertylauncher.feature.weather" to "weather",
        "com.example.games" to "games",
        "com.example.games.extra" to "extra",
    )

    @Test
    fun blamesTheModuleWhoseCodeRan() {
        assertEquals("weather", blame(thrownIn("com.newhost.qwertylauncher.feature.weather.WeatherModule\$install\$1")))
        assertEquals("extra", blame(thrownIn("com.example.games.extra.Board")))
        assertEquals("games", blame(thrownIn("com.example.games.Board")))
    }

    @Test
    fun looksThroughCauses() {
        assertEquals("games", blame(RuntimeException("outer", thrownIn("com.example.games.Board")).apply { stackTrace = arrayOf() }))
    }

    @Test
    fun theLaunchersOwnCodeBlamesNobody() {
        assertNull(blame(thrownIn("com.newhost.qwertylauncher.home.TileController")))
        // A module claiming the launcher's own package would be blamed for everything.
        val greedy = mapOf("com.newhost" to "greedy")
        assertNull(CrashGuard.blame(thrownIn("com.newhost.qwertylauncher.home.TileController"), greedy, OWN))
    }

    private fun blame(e: Throwable) = CrashGuard.blame(e, packages, OWN)

    private companion object {
        const val OWN = "com.newhost.qwertylauncher"
    }
}
