package com.newhost.qwertylauncher.feature.apps

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTypesTest {

    private fun classify(
        role: AppType? = null,
        category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
        game: Boolean = false,
        system: Boolean = false,
    ) = AppTypes.classify(role, category, game, system)

    @Test
    fun whatAnAppDoesWinsOverItsCategory() {
        // A browser that calls itself a productivity app is still a browser.
        assertEquals(AppType.WEB, classify(role = AppType.WEB, category = ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(AppType.CALLS, classify(role = AppType.CALLS, system = true))
    }

    @Test
    fun theDeclaredCategoryIsUsedNext() {
        assertEquals(AppType.GAMES, classify(category = ApplicationInfo.CATEGORY_GAME))
        assertEquals(AppType.MUSIC, classify(category = ApplicationInfo.CATEGORY_AUDIO))
        assertEquals(AppType.VIDEO, classify(category = ApplicationInfo.CATEGORY_VIDEO, system = true))
        assertEquals(AppType.PHOTOS, classify(category = ApplicationInfo.CATEGORY_IMAGE))
        assertEquals(AppType.SOCIAL, classify(category = ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(AppType.NEWS, classify(category = ApplicationInfo.CATEGORY_NEWS))
        assertEquals(AppType.MAPS, classify(category = ApplicationInfo.CATEGORY_MAPS))
        assertEquals(AppType.WORK, classify(category = ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(AppType.TOOLS, classify(category = ApplicationInfo.CATEGORY_ACCESSIBILITY))
    }

    @Test
    fun withoutEitherOldGamesThenPreinstalledAppsThenOther() {
        assertEquals(AppType.GAMES, classify(game = true, system = true))
        assertEquals(AppType.SYSTEM, classify(system = true))
        assertEquals(AppType.OTHER, classify())
    }

    @Test
    fun typesAreFoundByTheirId() {
        AppType.entries.forEach { assertEquals(it, AppType.byId(it.id)) }
        assertNull(AppType.byId("nope"))
    }
}
