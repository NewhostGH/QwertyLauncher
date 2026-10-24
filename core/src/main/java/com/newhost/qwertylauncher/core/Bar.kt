package com.newhost.qwertylauncher.core

import android.graphics.drawable.Drawable
import kotlinx.coroutines.flow.Flow

/**
 * Quick buttons in the top bar, beside the help and config buttons, at hand
 * from anywhere on home: like a phone's quick settings, in text. Each
 * [BarButton] is drawn exactly like those buttons (ASCII art, the button
 * size setting, the button frames setting), and the home cursor steps
 * through them. The user moves the strip along the top like the buttons.
 *
 * The top bar is one line of the screen's width: the shell works out how
 * many buttons fit beside the others (and the camera cutout) and tells the
 * strip with [onRoom], again whenever that changes. Buttons past the room
 * aren't shown, so offer no more than that.
 */
interface BarStrip {
    val id: String
    val title: String

    /** The buttons, left to right; re-emit whenever one changes. */
    fun buttons(): Flow<List<BarButton>>

    /** How many buttons fit in the top bar now. */
    fun onRoom(buttons: Int) {}
}

/**
 * A top bar button: [icon] drawn as ASCII art like the wrench, [short] in
 * its place without the ascii art module. Focusing it shows [label] on the
 * idle line, so say what it is and what enter does ("bluetooth on: enter
 * turns it off"). Enter or a tap runs [action]; holding enter shows [menu].
 * [key] names it, so the cursor stays on it across updates.
 */
class BarButton(
    val key: String,
    val label: String,
    val short: String,
    val icon: () -> Drawable,
    val menu: List<MenuItem> = emptyList(),
    val action: ResultAction,
)
