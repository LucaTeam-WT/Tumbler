package com.phucho0237.lucaauth.ui.accounts

/**
 * Pure helpers for the smooth countdown ring. The view model still ticks once per second; the
 * ring starts each second at [ringProgressAtStart] and sweeps linearly to [ringProgressAtEnd]
 * over that second, so it moves continuously between ticks.
 */

/** Ring fill at the moment a tick reports [secondsRemaining] (`1..period` for a live TOTP row). */
internal fun ringProgressAtStart(secondsRemaining: Int, period: Int): Float =
    (secondsRemaining.toFloat() / period).coerceIn(0f, 1f)

/** Ring fill one second later, the value the sweep of the current second ends at. */
internal fun ringProgressAtEnd(secondsRemaining: Int, period: Int): Float =
    ringProgressAtStart((secondsRemaining - 1).coerceAtLeast(0), period)

/**
 * Whether the ring must jump (snap) to the start value of the new second instead of animating
 * there from where it is. Only a normal tick, exactly one second after the previous one, keeps
 * the sweep going. The first tick, a period restart (the remaining seconds jump back up, so the
 * ring would otherwise sweep backwards) and a gap of several seconds (for example after the app
 * was paused) all snap.
 */
internal fun ringShouldSnap(previousSeconds: Int?, currentSeconds: Int): Boolean =
    previousSeconds == null || currentSeconds != previousSeconds - 1
