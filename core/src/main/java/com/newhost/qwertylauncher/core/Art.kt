package com.newhost.qwertylauncher.core

import kotlinx.coroutines.flow.Flow

/**
 * Animations for ASCII art, offered as a service by the ascii art module.
 * Every frame is text of the same shape as the art, redrawn in place, the
 * way a terminal animates: nothing fades, slides or scales. Each kind can be
 * switched off in config; when it is, its flow emits just the finished art.
 * Colours (spans) of the art are kept in every frame.
 */
interface ArtAnimator {
    /** The art types itself in, a few lines at a time. */
    fun drawIn(art: CharSequence): Flow<CharSequence>

    /**
     * From [old] to [new]: the cells that change scramble for a moment, then
     * settle, like a split-flap board. Art of a different shape is drawn in.
     */
    fun transition(old: CharSequence, new: CharSequence): Flow<CharSequence>

    /** The art of a selected item flickers: characters step up and down in density. Never completes. */
    fun shimmer(art: CharSequence): Flow<CharSequence>

    /** Text decoding itself out of random characters, left to right. */
    fun decode(text: CharSequence): Flow<CharSequence>

    /**
     * Frame numbers for art that moves by itself (weather, a ringing bell, a
     * charging battery), one every [periodMs], forever. While moving art is
     * switched off, just 0.
     */
    fun frames(periodMs: Long): Flow<Int>
}
