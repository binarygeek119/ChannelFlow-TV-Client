package org.jellyfin.androidtv.channelflow

import java.io.File
import java.net.URLEncoder
import java.util.UUID

object ChannelFlowVlcPlaylist {
	const val USER_AGENT = "ChannelFlow-TV"

	/** Jitter buffer before playback starts. Keep short so live channel changes are not delayed. */
	const val START_CACHING_MS = 1_500

	/**
	 * Keep reading from the server after the jitter buffer is full, up to ~10 minutes of media
	 * at 8 Mbps (600 MiB). ChannelFlow fans out a shared encoder; pausing the HTTP socket drops packets.
	 */
	const val PREFETCH_BUFFER_KIB = 600 * 1_024
	const val PREFETCH_READ_SIZE = 262_144

	fun text(
		streamUrl: String,
		name: String = "ChannelFlow",
		channelId: UUID? = null,
		number: String? = null,
		logoUrl: String? = null,
		apiKey: String? = null,
	): String {
		val title = escape(name.ifBlank { "ChannelFlow" })
		val playUrl = withApiKey(streamUrl.trim(), apiKey)
		val extinf = buildString {
			append("#EXTINF:-1")
			if (channelId != null) append(" tvg-id=\"").append(channelId.toString().replace("-", "")).append('"')
			if (!number.isNullOrBlank()) append(" tvg-chno=\"").append(escape(number)).append('"')
			append(" tvg-name=\"").append(title).append('"')
			if (!logoUrl.isNullOrBlank()) append(" tvg-logo=\"").append(escape(logoUrl)).append('"')
			append(',').append(title)
		}
		return buildString {
			appendLine("#EXTM3U")
			appendLine(extinf)
			appendLine("#EXTVLCOPT:http-user-agent=$USER_AGENT")
			appendLine("#EXTVLCOPT:network-caching=$START_CACHING_MS")
			appendLine("#EXTVLCOPT:live-caching=$START_CACHING_MS")
			appendLine("#EXTVLCOPT:prefetch-buffer-size=$PREFETCH_BUFFER_KIB")
			appendLine("#EXTVLCOPT:prefetch-read-size=$PREFETCH_READ_SIZE")
			appendLine("#EXTVLCOPT:ts-cc-check=0")
			if (!apiKey.isNullOrBlank()) appendLine("#EXTVLCOPT:http-header=X-Api-Key: $apiKey")
			appendLine(playUrl)
		}
	}

	fun write(
		file: File,
		streamUrl: String,
		name: String = "ChannelFlow",
		channelId: UUID? = null,
		number: String? = null,
		logoUrl: String? = null,
		apiKey: String? = null,
	): File {
		file.parentFile?.mkdirs()
		file.writeText(text(streamUrl, name, channelId, number, logoUrl, apiKey), Charsets.UTF_8)
		return file
	}

	fun streamUrlFrom(text: String): String? =
		M3uParser.parse(text).firstOrNull()?.streamUrl

	fun withApiKey(url: String, apiKey: String?): String {
		if (apiKey.isNullOrBlank() || url.contains("apiKey=", ignoreCase = true)) return url
		val encoded = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
		val separator = if (url.contains('?')) '&' else '?'
		return "$url${separator}apiKey=$encoded"
	}

	fun withLiveEdge(url: String, now: Long = System.currentTimeMillis()): String {
		val base = url.substringBefore('#')
		val fragment = url.substringAfter('#', missingDelimiterValue = "")
		val path = base.substringBefore('?')
		val query = base.substringAfter('?', missingDelimiterValue = "")
		val params = query.split('&').filter { it.isNotEmpty() && !it.startsWith("_r=") }.toMutableList()
		params += "_r=$now"
		val rebuilt = "$path?${params.joinToString("&")}"
		return if (url.contains('#')) "$rebuilt#$fragment" else rebuilt
	}

	private fun escape(value: String): String =
		value.replace(',', ' ').replace('\n', ' ').replace('"', '\'')
}
