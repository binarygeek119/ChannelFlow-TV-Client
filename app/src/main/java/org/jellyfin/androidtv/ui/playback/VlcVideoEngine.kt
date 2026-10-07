package org.jellyfin.androidtv.ui.playback

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.jellyfin.androidtv.channelflow.ChannelFlowLiveReconnect
import org.jellyfin.androidtv.channelflow.ChannelFlowStream
import org.jellyfin.androidtv.channelflow.ChannelFlowVlcPlaylist
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import timber.log.Timber
import java.io.File
import java.util.UUID

class VlcVideoEngine(
	private val activity: Activity,
	private val videoLayout: VLCVideoLayout,
	private val helper: PlaybackOverlayFragmentHelper,
) {
	private val libVlc: LibVLC
	private var player: MediaPlayer
	private val handler = Handler(Looper.getMainLooper())
	private var notifier: PlaybackControllerNotifiable? = null
	private var attached = false
	private var liveStream = false
	private var lastPosition = 0L
	private var lastUrl: String? = null
	private var lastApiKey: String? = null
	private var retriedSoft = false
	private var released = false
	private var userStopped = false
	private var userPaused = false
	private var reconnectScheduled = false
	private var reconnectAttempt = 0
	private var suppressStopUntil = 0L
	private var lastTimeChangedAt = 0L
	private var hadPlaying = false
	private var waitingForPlayingSince = 0L

	init {
		val options = arrayListOf(
			"--network-caching=${ChannelFlowVlcPlaylist.START_CACHING_MS}",
			"--live-caching=${ChannelFlowVlcPlaylist.START_CACHING_MS}",
			"--prefetch-buffer-size=${ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB}",
			"--prefetch-read-size=${ChannelFlowVlcPlaylist.PREFETCH_READ_SIZE}",
			"--http-user-agent=${ChannelFlowVlcPlaylist.USER_AGENT}",
			"--aout=opensles",
			"--audio-time-stretch",
		)
		libVlc = LibVLC(activity, options)
		player = createPlayer()
	}

	fun subscribe(notifier: PlaybackControllerNotifiable) {
		this.notifier = notifier
	}

	fun isPlaying(): Boolean = player.isPlaying

	fun getCurrentPosition(): Long {
		if (!player.isPlaying) return lastPosition
		val time = player.time.coerceAtLeast(0L)
		lastPosition = time
		return time
	}

	fun getDuration(): Long = player.length.coerceAtLeast(0L)

	fun isSeekable(): Boolean = !liveStream && player.isSeekable

	fun seekTo(position: Long): Long {
		if (!isSeekable()) return -1L
		player.time = position
		lastPosition = position
		return position
	}

	fun getPlaybackSpeed(): Float = player.rate

	fun setPlaybackSpeed(speed: Float) {
		if (speed < 0.25f) return
		player.rate = speed
	}

	fun setZoom(mode: ZoomMode) {
		player.videoScale = when (mode) {
			ZoomMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
			ZoomMode.AUTO_CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
			ZoomMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
		}
	}

	fun setMedia(
		url: String,
		live: Boolean,
		name: String? = null,
		channelId: UUID? = null,
		number: String? = null,
		logoUrl: String? = null,
		apiKey: String? = null,
	) {
		liveStream = live
		lastUrl = url
		lastApiKey = apiKey
		retriedSoft = false
		userStopped = false
		userPaused = false
		reconnectAttempt = 0
		waitingForPlayingSince = 0L
		cancelReconnect()
		hadPlaying = false
		lastTimeChangedAt = SystemClock.elapsedRealtime()
		attachIfNeeded()
		val playlist = ChannelFlowVlcPlaylist.write(
			file = File(File(activity.cacheDir, "vlc"), "channel.m3u"),
			streamUrl = url,
			name = name ?: "ChannelFlow",
			channelId = channelId,
			number = number,
			logoUrl = logoUrl,
			apiKey = apiKey,
		)
		val playUrl = ChannelFlowVlcPlaylist.streamUrlFrom(playlist.readText())
			?: ChannelFlowVlcPlaylist.withApiKey(url, apiKey)
		val media = mediaFromPlaylist(playlist) ?: mediaFromUrl(playUrl, preferHardware = true)
		player.media = media
		media.release()
		Timber.i("VLC playing M3U live=%s url=%s", live, ChannelFlowStream.redact(playUrl))
	}

	fun start() {
		userStopped = false
		userPaused = false
		attachIfNeeded()
		player.play()
	}

	fun play() {
		userPaused = false
		userStopped = false
		player.play()
	}

	fun pause() {
		userPaused = true
		cancelReconnect()
		player.pause()
	}

	fun stop() {
		userStopped = true
		cancelReconnect()
		runCatching { player.stop() }
		helper.setScreensaverLock(false)
	}

	fun release() {
		released = true
		notifier = null
		cancelReconnect()
		stop()
		if (attached) {
			runCatching { player.detachViews() }
			attached = false
		}
		runCatching { player.release() }
		runCatching { libVlc.release() }
	}

	fun surface(): View = videoLayout

	private fun createPlayer(): MediaPlayer {
		val created = MediaPlayer(libVlc)
		created.setEventListener { event ->
			activity.runOnUiThread { onEvent(event) }
		}
		return created
	}

	private fun mediaFromPlaylist(file: File): Media? {
		val playlist = Media(libVlc, file.absolutePath)
		var stream: Media? = null
		try {
			if (!playlist.parse(IMedia.Parse.ParseLocal)) return null
			val items = playlist.subItems()
			try {
				if (items.count <= 0) return null
				stream = items.getMediaAt(0) as? Media ?: return null
				applyStreamOptions(stream, preferHardware = true)
				Timber.i("VLC opened M3U subitem uri=%s", stream.uri)
			} finally {
				items.release()
			}
		} catch (error: Throwable) {
			Timber.w(error, "VLC could not parse M3U playlist")
			stream = null
		} finally {
			playlist.release()
		}
		return stream
	}

	private fun mediaFromUrl(url: String, preferHardware: Boolean): Media {
		val media = Media(libVlc, Uri.parse(url))
		applyStreamOptions(media, preferHardware)
		return media
	}

	private fun applyStreamOptions(media: Media, preferHardware: Boolean) {
		media.setHWDecoderEnabled(preferHardware, true)
		media.addOption(":network-caching=${ChannelFlowVlcPlaylist.START_CACHING_MS}")
		media.addOption(":prefetch-buffer-size=${ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB}")
		media.addOption(":prefetch-read-size=${ChannelFlowVlcPlaylist.PREFETCH_READ_SIZE}")
		media.addOption(":ts-cc-check=0")
		media.addOption(":http-user-agent=${ChannelFlowVlcPlaylist.USER_AGENT}")
		val apiKey = lastApiKey
		if (!apiKey.isNullOrBlank()) media.addOption(":http-header=X-Api-Key: $apiKey")
		if (liveStream) {
			media.addOption(":live-caching=${ChannelFlowVlcPlaylist.START_CACHING_MS}")
			media.addOption(":clock-jitter=0")
			media.addOption(":clock-synchro=0")
		}
		val url = media.uri?.toString() ?: lastUrl.orEmpty()
		if (ChannelFlowStream.isMpegTs(url) && !ChannelFlowStream.isHls(url)) {
			media.addOption(":demux=ts")
		}
	}

	private fun attachIfNeeded() {
		if (attached) return
		videoLayout.visibility = View.VISIBLE
		player.attachViews(videoLayout, null, false, false)
		attached = true
	}

	private fun onEvent(event: MediaPlayer.Event) {
		when (event.type) {
			MediaPlayer.Event.Playing -> {
				hadPlaying = true
				waitingForPlayingSince = 0L
				reconnectAttempt = 0
				retriedSoft = false
				reconnectScheduled = false
				lastTimeChangedAt = SystemClock.elapsedRealtime()
				notifier?.onPrepared()
				helper.setScreensaverLock(true)
				if (liveStream) startWatchdog()
			}
			MediaPlayer.Event.Paused -> {
				helper.setScreensaverLock(false)
			}
			MediaPlayer.Event.Stopped -> {
				helper.setScreensaverLock(false)
				if (SystemClock.elapsedRealtime() < suppressStopUntil) return
				if (hadPlaying) scheduleReconnect("stopped")
			}
			MediaPlayer.Event.EndReached -> {
				if (liveStream) {
					scheduleReconnect("ended")
				} else {
					notifier?.onCompletion()
				}
			}
			MediaPlayer.Event.EncounteredError -> {
				if (liveStream) {
					val preferHardware = retriedSoft || reconnectAttempt < 2
					if (!preferHardware) retriedSoft = true
					scheduleReconnect("error", preferHardware)
				} else if (!retriedSoft && lastUrl != null) {
					retriedSoft = true
					Timber.w("VLC playback error; retrying stream without hardware decode")
					restart(preferHardware = false)
				} else {
					Timber.e("VLC playback error")
					notifier?.onError()
				}
			}
			MediaPlayer.Event.TimeChanged -> {
				lastTimeChangedAt = SystemClock.elapsedRealtime()
				notifier?.onProgress()
			}
		}
	}

	private fun scheduleReconnect(reason: String, preferHardware: Boolean = true) {
		if (!ChannelFlowLiveReconnect.shouldReconnect(liveStream, userStopped, userPaused, reconnectScheduled)) return
		if (released) return
		reconnectScheduled = true
		val attempt = reconnectAttempt
		reconnectAttempt++
		val delay = ChannelFlowLiveReconnect.delayMs(attempt)
		Timber.w("VLC live stream %s; reconnecting in %sms (attempt %s)", reason, delay, reconnectAttempt)
		handler.postDelayed({
			if (released || userStopped || userPaused) {
				reconnectScheduled = false
				return@postDelayed
			}
			restart(preferHardware)
		}, delay)
	}

	private fun restart(preferHardware: Boolean) {
		val url = lastUrl ?: run {
			reconnectScheduled = false
			return
		}
		suppressStopUntil = SystemClock.elapsedRealtime() + ChannelFlowLiveReconnect.SUPPRESS_STOP_MS
		lastTimeChangedAt = SystemClock.elapsedRealtime()
		hadPlaying = false
		waitingForPlayingSince = SystemClock.elapsedRealtime()
		val playUrl = ChannelFlowVlcPlaylist.withLiveEdge(ChannelFlowVlcPlaylist.withApiKey(url, lastApiKey))
		recreatePlayer()
		val media = mediaFromUrl(playUrl, preferHardware)
		player.media = media
		media.release()
		player.play()
		reconnectScheduled = false
		startWatchdog()
		Timber.i("VLC reopened live stream url=%s hw=%s", ChannelFlowStream.redact(playUrl), preferHardware)
	}

	private fun recreatePlayer() {
		if (attached) {
			runCatching { player.detachViews() }
			attached = false
		}
		runCatching { player.stop() }
		runCatching { player.release() }
		player = createPlayer()
		attachIfNeeded()
	}

	private fun startWatchdog() {
		handler.removeCallbacks(watchdog)
		handler.postDelayed(watchdog, ChannelFlowLiveReconnect.WATCHDOG_MS)
	}

	private fun cancelReconnect() {
		reconnectScheduled = false
		waitingForPlayingSince = 0L
		handler.removeCallbacksAndMessages(null)
	}

	private val watchdog = object : Runnable {
		override fun run() {
			if (released || !liveStream || userStopped || userPaused) return
			val now = SystemClock.elapsedRealtime()
			if (!reconnectScheduled) {
				when {
					hadPlaying && now - lastTimeChangedAt >= ChannelFlowLiveReconnect.STALL_MS ->
						scheduleReconnect("decoder stall")
					waitingForPlayingSince > 0L && !hadPlaying &&
						now - waitingForPlayingSince >= ChannelFlowLiveReconnect.REOPEN_TIMEOUT_MS ->
						scheduleReconnect("reopen timeout")
				}
			}
			if (!released && liveStream && !userStopped) {
				handler.postDelayed(this, ChannelFlowLiveReconnect.WATCHDOG_MS)
			}
		}
	}
}
