package com.newhost.qwertylauncher.feature.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ShapeIcon
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.Tty
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.newhost.qwertylauncher.core.R as CoreR

/** The network: the connection drawn as art beside whether it's online, and its facts. */
internal class NetWidget(private val context: Context) : HomeWidget {
    override val id = "net"
    override val title = "network"
    override val order = 35

    override fun render(): Flow<WidgetContent> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(content(caps))
            }

            override fun onLost(network: Network) {
                trySend(content(null))
            }
        }
        // Nothing is reported while offline, so start from the current state.
        trySend(content(connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }))
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    override fun onClick(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    /**
     * The connection drawn as art (wifi fans or mobile bars, lit to the
     * signal), whether the internet is reachable, and facts: metered or not,
     * speeds, signal. [caps] null is offline.
     */
    private fun content(caps: NetworkCapabilities?): WidgetContent {
        if (caps == null) {
            val headline = colored("offline", CoreR.color.tty_red)
            val picture = Cell.Art("net:off", Size(PICTURE_COLUMNS, PICTURE_LINES)) { signal(wifi = true, level = 0, color = IconColors.RED) }
            return WidgetContent(headline, layout = tile(picture, headline))
        }
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val type = when {
            wifi -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "network"
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val level = signalLevel(caps)
        val color = if (online) IconColors.GREEN else IconColors.YELLOW
        val picture = Cell.Art("net:$wifi:$level:$online", Size(PICTURE_COLUMNS, PICTURE_LINES)) {
            signal(wifi, level, color)
        }
        val headline = SpannableStringBuilder().apply {
            append("$type ")
            append(if (online) "online" else "no internet", ForegroundColorSpan(context.getColor(if (online) CoreR.color.tty_cyan else CoreR.color.tty_yellow)), SPAN)
        }
        val down = caps.linkDownstreamBandwidthKbps / KBPS
        val up = caps.linkUpstreamBandwidthKbps / KBPS
        val facts = listOfNotNull(
            fact("net:metered:$metered", if (metered) "metered" else "unmetered", if (metered) COIN else INFINITE),
            if (down > 0) fact("net:down", "down $down Mb/s", DOWN) else null,
            if (up > 0) fact("net:up", "up $up Mb/s", UP) else null,
            fact("net:signal:$level", "signal ${Tty.signal(level)}", BARS),
        )
        return WidgetContent(headline, layout = tile(picture, headline, facts))
    }

    /** 0 to 4 bars from the reported strength (wifi RSSI in dBm); full when unknown but connected. */
    private fun signalLevel(caps: NetworkCapabilities): Int {
        val strength = caps.signalStrength
        if (strength == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return LEVELS
        return ((strength + 100) / 12).coerceIn(0, LEVELS)
    }

    private fun colored(text: String, color: Int): CharSequence = SpannableStringBuilder().apply {
        append(text, ForegroundColorSpan(context.getColor(color)), SPAN)
    }

    private companion object {
        const val LEVELS = 4
        const val KBPS = 1000
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Wifi fans or mobile bars, the first [level] of four lit in [color], the rest grey. */
        fun signal(wifi: Boolean, level: Int, color: Int) = ShapeIcon {
            if (wifi) {
                fill(if (level > 0) color else IconColors.GREY); circle(50f, 84f, 8f)
                for (i in 1..3) {
                    stroke(if (level > i) color else IconColors.GREY, 9f)
                    arc(50f - i * 22f, 84f - i * 22f, 50f + i * 22f, 84f + i * 22f, 225f, 90f)
                }
            } else {
                for (i in 0 until LEVELS) {
                    fill(if (level > i) color else IconColors.GREY)
                    rect(10f + i * 22f, 80f - (i + 1) * 18f, 26f + i * 22f, 90f)
                }
            }
        }

        val DOWN = icon { fill(IconColors.CYAN); rect(40f, 8f, 60f, 56f); polygon(20f, 52f, 80f, 52f, 50f, 92f) }
        val UP = icon { fill(IconColors.GREEN); rect(40f, 44f, 60f, 92f); polygon(20f, 48f, 80f, 48f, 50f, 8f) }
        val COIN = icon {
            fill(IconColors.YELLOW); circle(50f, 50f, 40f)
            fill(IconColors.ORANGE); circle(50f, 50f, 26f)
        }
        val INFINITE = icon {
            stroke(IconColors.GREEN, 10f); circle(32f, 50f, 18f); circle(68f, 50f, 18f)
        }
        val BARS = icon {
            fill(IconColors.GREEN)
            for (i in 0 until LEVELS) rect(10f + i * 22f, 80f - (i + 1) * 18f, 26f + i * 22f, 90f)
        }
    }
}
