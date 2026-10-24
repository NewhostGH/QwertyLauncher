package com.newhost.qwertylauncher.feature.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR
import com.newhost.qwertylauncher.core.ShapeIcon

/** The next alarm: an ASCII alarm clock, the time, and how long until it rings. */
internal class NextAlarmWidget(
    private val context: Context,
) : HomeWidget {
    override val id = "alarm"
    override val order = 40

    override fun render(): Flow<WidgetContent> = channelFlow {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val changed = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                launch { send(content(alarms)) }
            }
        }
        context.registerReceiver(
            changed,
            IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        // The countdown changes every minute even when the alarm doesn't.
        launch {
            while (true) {
                send(content(alarms))
                delay(MINUTE_MS)
            }
        }
        awaitClose { context.unregisterReceiver(changed) }
    }

    override fun onClick(host: LauncherHost) {
        host.startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    /** The alarm clock drawn as art (amber when set), when it rings, and a countdown. */
    private fun content(alarms: AlarmManager): WidgetContent {
        val next = alarms.nextAlarmClock?.triggerTime
        val set = next != null
        val picture = Cell.Art("alarm:$set", Size(PICTURE_COLUMNS, PICTURE_LINES)) {
            val color = context.getColor(if (set) CoreR.color.tty_accent else CoreR.color.tty_dim)
            ShapeIcon { fill(color); path(ALARM_CLOCK) }
        }
        if (next == null) {
            val headline = dim("no alarm set")
            return WidgetContent(headline, layout = tile(picture, headline))
        }
        val time = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
        val until = Duration.ofMillis(next - System.currentTimeMillis()).coerceAtLeast(Duration.ZERO)
        val countdown = if (until.toDays() > 0) {
            "in ${until.toDays()}d ${until.toHours() % 24}h"
        } else {
            "in ${until.toHours()}h ${until.toMinutes() % 60}m"
        }
        val headline = time.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())).lowercase()
        val facts = listOf(fact("alarm:countdown", countdown, HOURGLASS))
        return WidgetContent(headline, layout = tile(picture, headline, facts))
    }

    private fun dim(text: String): CharSequence = SpannableStringBuilder().apply {
        append(text, ForegroundColorSpan(context.getColor(CoreR.color.tty_dim)), SPAN)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3

        /** Material's "alarm". */
        const val ALARM_CLOCK = "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85z" +
            "M12.5,8L11,8v6l4.75,2.85 0.75,-1.23 -4,-2.37L12.5,8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9" +
            "s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        val HOURGLASS = icon {
            fill(IconColors.GREY); rect(18f, 6f, 82f, 14f); rect(18f, 86f, 82f, 94f)
            fill(IconColors.WHITE); polygon(24f, 14f, 76f, 14f, 50f, 50f); polygon(24f, 86f, 76f, 86f, 50f, 50f)
            fill(IconColors.YELLOW); polygon(36f, 30f, 64f, 30f, 50f, 48f); polygon(30f, 86f, 70f, 86f, 50f, 64f)
        }
    }
}
