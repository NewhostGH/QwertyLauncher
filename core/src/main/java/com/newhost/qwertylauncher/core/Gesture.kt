package com.newhost.qwertylauncher.core

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Which way a drag goes. */
enum class DragAxis { VERTICAL, HORIZONTAL }

/**
 * A drag as the finger really moved: every sample of it, the in-between
 * ones Android batches into each event too. People don't drag in straight
 * lines: a scroll down leans and curves, a swipe sideways dips. So which
 * way it goes is judged from how far it has got, not its first pixels: within
 * [TOLERANCE] degrees of an axis, it's that axis; in between, it waits until
 * it has gone [DECIDE_SLOPS] times the touch slop, then takes the larger.
 */
class DragPath(context: Context) {
    private class Sample(val x: Float, val y: Float, val time: Long)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val samples = ArrayList<Sample>()

    /** Which way it goes, once that's clear; null until then. */
    var axis: DragAxis? = null
        private set

    val dx: Float get() = if (samples.isEmpty()) 0f else samples.last().x - samples.first().x
    val dy: Float get() = if (samples.isEmpty()) 0f else samples.last().y - samples.first().y

    /** How far it has gone, as the crow flies. */
    val distance: Float get() = hypot(dx, dy)

    fun start(event: MotionEvent) {
        samples.clear()
        axis = null
        samples += Sample(event.x, event.y, event.eventTime)
    }

    /** Takes in [event], with the samples Android batched into it. */
    fun add(event: MotionEvent) {
        for (h in 0 until event.historySize) {
            samples += Sample(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h))
        }
        samples += Sample(event.x, event.y, event.eventTime)
    }

    /**
     * Judges which way it goes (see the class), and keeps to it once judged.
     * With [split] (degrees from sideways), no waiting in between: once it's
     * gone twice the slop, steeper than [split] is vertical, else sideways.
     * For a surface whose sideways slides arc up or down a lot (a keyboard's).
     */
    fun judge(split: Float? = null): DragAxis? {
        axis?.let { return it }
        if (distance <= slop) return null
        val angle = angleOf(dx, dy)
        if (split != null) {
            if (distance <= slop * 2) return null
            axis = if (angle >= split) DragAxis.VERTICAL else DragAxis.HORIZONTAL
            return axis
        }
        axis = when {
            angle >= RIGHT_ANGLE - TOLERANCE -> DragAxis.VERTICAL
            angle <= TOLERANCE -> DragAxis.HORIZONTAL
            distance > slop * DECIDE_SLOPS -> if (abs(dy) >= abs(dx)) DragAxis.VERTICAL else DragAxis.HORIZONTAL
            else -> null
        }
        return axis
    }

    /**
     * Whether its last [RECENT] samples go clearly across [axis] (further
     * than [TOLERANCE] from it): a real turn, not a wobble.
     */
    fun turning(axis: DragAxis): Boolean {
        if (samples.size < RECENT) return false
        val from = samples[samples.size - RECENT]
        val to = samples.last()
        val x = to.x - from.x
        val y = to.y - from.y
        if (hypot(x, y) < slop) return false
        val angle = angleOf(x, y)
        return if (axis == DragAxis.VERTICAL) angle < RIGHT_ANGLE - TOLERANCE else angle > TOLERANCE
    }

    /** How fast it was going at the end, in pixels a second, across and down: over its last [VELOCITY_MS]. */
    fun velocity(): Pair<Float, Float> {
        if (samples.size < 2) return 0f to 0f
        val end = samples.last()
        val start = samples.lastOrNull { end.time - it.time >= VELOCITY_MS } ?: samples.first()
        val seconds = (end.time - start.time).coerceAtLeast(1) / 1000f
        return (end.x - start.x) / seconds to (end.y - start.y) / seconds
    }

    /** For the input log: how it went. */
    fun describe(): String {
        val (vx, vy) = velocity()
        val ms = if (samples.size < 2) 0 else samples.last().time - samples.first().time
        return "drag ${samples.size} samples ${ms}ms dx=%.0f dy=%.0f angle=%.0f axis=%s v=%.0f,%.0f"
            .format(dx, dy, angleOf(dx, dy), axis?.name?.lowercase() ?: "none", vx, vy)
    }

    private fun angleOf(x: Float, y: Float) = Math.toDegrees(atan2(abs(y), abs(x)).toDouble()).toFloat()

    companion object {
        /** How far off an axis, in degrees, still counts as along it. */
        const val TOLERANCE = 30f
        private const val RIGHT_ANGLE = 90f
        private const val DECIDE_SLOPS = 3f
        private const val RECENT = 6
        private const val VELOCITY_MS = 80L
    }
}
