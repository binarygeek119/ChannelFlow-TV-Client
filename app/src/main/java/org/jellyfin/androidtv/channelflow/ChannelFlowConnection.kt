package org.jellyfin.androidtv.channelflow

import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * One reachable form of a ChannelFlow server: the base URL together with the Live TV playlist
 * and listings URLs served from that same origin.
 */
@Serializable
data class ChannelFlowEndpoint(
	val baseUrl: String,
	val m3uUrl: String,
	val epgUrl: String,
) {
	fun withApiKey(newKey: String): ChannelFlowEndpoint = copy(
		m3uUrl = ChannelFlowUrls.withApiKey(m3uUrl, newKey),
		epgUrl = ChannelFlowUrls.withApiKey(epgUrl, newKey),
	)
}

/**
 * How the app picks between the public and local endpoint of a paired server.
 * [AUTO] probes the local URL and falls back to the public one.
 */
@Serializable
enum class ChannelFlowEndpointMode { AUTO, LOCAL, PUBLIC }

@Serializable
data class ChannelFlowConnection(
	val baseUrl: String,
	val m3uUrl: String,
	val epgUrl: String,
	val apiKey: String,
	/** Internet reachable Live TV URLs, when the server handed out both variants. */
	val publicEndpoint: ChannelFlowEndpoint? = null,
	/** Same LAN Live TV URLs, when the server handed out both variants. */
	val localEndpoint: ChannelFlowEndpoint? = null,
) {
	val hasVariants: Boolean
		get() = publicEndpoint != null || localEndpoint != null

	/**
	 * The variant of this connection that [endpoint] belongs to, or null when it is the primary
	 * address (a server that only handed out one URL).
	 */
	fun routeOf(endpoint: ChannelFlowEndpoint): ChannelFlowRoute? {
		val local = localEndpoint?.baseUrl
		val public = publicEndpoint?.baseUrl
		return when {
			local != null && local.equals(endpoint.baseUrl, ignoreCase = true) -> ChannelFlowRoute.LOCAL
			public != null && public.equals(endpoint.baseUrl, ignoreCase = true) -> ChannelFlowRoute.PUBLIC
			else -> null
		}
	}

	fun displayName(): String {
		val uri = runCatching { Uri.parse(baseUrl) }.getOrNull() ?: return baseUrl
		val host = uri.host?.removePrefix("www.").orEmpty()
		if (host.isBlank()) return baseUrl
		val port = uri.port
		return if (port != -1) "$host:$port" else host
	}

	fun withResolvedApiKey(): ChannelFlowConnection {
		val key = apiKey.ifBlank { ChannelFlowUrls.extractApiKey(m3uUrl) }
			.ifBlank { ChannelFlowUrls.extractApiKey(epgUrl) }
			.ifBlank { publicEndpoint?.m3uUrl?.let(ChannelFlowUrls::extractApiKey).orEmpty() }
			.ifBlank { localEndpoint?.m3uUrl?.let(ChannelFlowUrls::extractApiKey).orEmpty() }
		return if (key == apiKey) this else copy(apiKey = key)
	}

	fun withApiKey(newKey: String): ChannelFlowConnection {
		val key = newKey.trim()
		if (key.isBlank() || key == apiKey) return this
		return copy(
			apiKey = key,
			m3uUrl = ChannelFlowUrls.withApiKey(m3uUrl, key),
			epgUrl = ChannelFlowUrls.withApiKey(epgUrl, key),
			publicEndpoint = publicEndpoint?.withApiKey(key),
			localEndpoint = localEndpoint?.withApiKey(key),
		)
	}

	/**
	 * Move a URL found inside a playlist or the listings from the base of the endpoint the app
	 * is *not* using to the base it is using. The server builds absolute stream and logo URLs
	 * from its configured public base URL, so a guide fetched over the local URL still points at
	 * the public host unless the app rewrites it.
	 */
	fun remapUrl(url: String, activeBaseUrl: String): String {
		val local = localEndpoint?.baseUrl ?: return url
		val public = publicEndpoint?.baseUrl ?: return url
		val active = activeBaseUrl.trim().trimEnd('/')
		val other = when {
			local.equals(public, ignoreCase = true) -> return url
			active.equals(local, ignoreCase = true) -> public
			active.equals(public, ignoreCase = true) -> local
			else -> return url
		}
		return ChannelFlowUrls.replaceBase(url, other, active) ?: url
	}
}

@Serializable
data class ChannelFlowSavedServer(
	val id: String,
	val connection: ChannelFlowConnection,
	val endpointMode: ChannelFlowEndpointMode = ChannelFlowEndpointMode.AUTO,
)
