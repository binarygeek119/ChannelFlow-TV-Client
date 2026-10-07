package org.jellyfin.androidtv.channelflow

/**
 * Live MPEG-TS reconnect policy for ChannelFlow.
 *
 * Emergency alerts flush the shared server buffer and reset the encoder. The HTTP
 * socket then ends or the decoder stops advancing. Reopen the live edge on those
 * events; do not treat a slow first buffer or frozen libVLC byte counters as failure.
 */
object ChannelFlowLiveReconnect {
	const val STALL_MS = 8_000L
	const val REOPEN_TIMEOUT_MS = 20_000L
	const val SUPPRESS_STOP_MS = 1_500L
	const val WATCHDOG_MS = 1_000L

	private val DELAYS_MS = longArrayOf(250L, 500L, 1_000L, 2_000L, 3_000L, 5_000L)

	fun delayMs(attempt: Int): Long {
		if (attempt <= 0) return DELAYS_MS.first()
		return DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.lastIndex)]
	}

	fun shouldReconnect(
		live: Boolean,
		userStopped: Boolean,
		userPaused: Boolean,
		alreadyScheduled: Boolean,
	): Boolean = live && !userStopped && !userPaused && !alreadyScheduled
}
