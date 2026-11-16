package com.newhost.qwertylauncher.core

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRoutingTest {

    @Test
    fun aKeyTheSurfaceUsedIsAlwaysKept() {
        assertTrue(KeyRouting.keep(used = true, KeyEvent.KEYCODE_M))
        assertTrue(KeyRouting.keep(used = true, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun anUnusedKeyIsDroppedNotPassedOn() {
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB)) {
            assertTrue("key $code", KeyRouting.keep(used = false, code))
        }
    }

    @Test
    fun unusedSystemKeysReachAndroid() {
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_POWER)) {
            assertFalse("key $code", KeyRouting.keep(used = false, code))
        }
    }
}
