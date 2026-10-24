package com.newhost.qwertylauncher.ui

import android.widget.TextView
import com.newhost.qwertylauncher.core.ArtAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Plays ASCII art into [view] with the installed [ArtAnimator]: new art draws
 * itself in, changed art rolls over, frames of the art's own animation show
 * as they come, and selected art flickers. Without an animator (the ascii
 * art module off), art just appears. Runs in [scope], so it stops with it.
 */
internal class ArtPlayer(
    private val view: TextView,
    private val scope: CoroutineScope,
    private val animator: () -> ArtAnimator?,
) {
    private var art: CharSequence? = null
    private var job: Job? = null
    private var selected = false

    /** Shows [new]; [live] marks a frame of the art's own animation. */
    fun show(new: CharSequence?, live: Boolean = false) {
        val old = art
        art = new
        val animator = animator()
        when {
            new == null || animator == null -> stopWith(new)
            old == null -> play(animator.drawIn(new))
            // Own frames don't interrupt a draw-in, a roll or the flicker; they show once those end.
            live -> if (job?.isActive != true) view.text = new
            old.toString() == new.toString() -> if (job?.isActive != true) view.text = new
            else -> play(animator.transition(old, new))
        }
    }

    /** Selected art flickers until it's unselected. */
    fun select(on: Boolean) {
        if (on == selected) return
        selected = on
        val current = art ?: return
        val animator = animator() ?: return
        if (on) play(animator.shimmer(current)) else stopWith(current)
    }

    private fun stopWith(text: CharSequence?) {
        job?.cancel()
        job = null
        view.text = text
    }

    private fun play(frames: Flow<CharSequence>) {
        job?.cancel()
        job = scope.launch {
            frames.collect { view.text = it }
            // Settled; selected art goes on to flicker.
            val settled = art ?: return@launch
            view.text = settled
            if (selected) animator()?.shimmer(settled)?.collect { view.text = it }
        }
    }
}
