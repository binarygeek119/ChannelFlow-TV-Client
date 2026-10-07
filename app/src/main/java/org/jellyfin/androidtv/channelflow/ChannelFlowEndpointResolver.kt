package org.jellyfin.androidtv.channelflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Which endpoint of a paired server the app decided to use. */
enum class ChannelFlowRoute { LOCAL, PUBLIC }

/** The route currently in use for one saved server, published for the UI and the guide. */
data class ChannelFlowActiveRoute(
	val serverId: String,
	val route: ChannelFlowRoute,
)

/** Reachability decision for the local endpoint of a server, as stored by a [ChannelFlowRouteCache]. */
data class ChannelFlowRouteCacheEntry(
	val route: ChannelFlowRoute,
	val at: Long,
	val localReachable: Boolean,
)

/** Small key/value store for endpoint decisions, kept separate from the connection store. */
interface ChannelFlowRouteCache {
	fun read(key: String): ChannelFlowRouteCacheEntry?
	fun write(key: String, entry: ChannelFlowRouteCacheEntry)
	fun remove(key: String)
	fun clear()
}

/**
 * Picks between the local and public endpoint of a paired ChannelFlow server.
 *
 * In [ChannelFlowEndpointMode.AUTO] the local URL is probed with a short `GET /health` and the
 * app stays on it while it answers, falling back to the public URL otherwise. The decision is
 * cached briefly, re-checked whenever the network changes, and flipped for good when a request
 * on the current route fails so the app heals itself when the TV moves between networks.
 */
class ChannelFlowEndpointResolver(
	private val cache: ChannelFlowRouteCache,
	private val probeLocal: suspend (String) -> Boolean = { probeHealth(it) },
	private val clock: () -> Long = System::currentTimeMillis,
) {
	constructor(context: Context) : this(cache = ChannelFlowRoutePreferences(context)) {
		watchNetwork(context.applicationContext)
	}

	private val _active = MutableStateFlow<ChannelFlowActiveRoute?>(null)
	private val firstEvent = AtomicBoolean(true)
	private val probeLock = Mutex()

	/** The route in use for the active server, or null while no variant has been chosen. */
	val active: StateFlow<ChannelFlowActiveRoute?> = _active.asStateFlow()

	/**
	 * Returns the endpoint to talk to for [connection]. Connections without public/local
	 * variants (paired with an older server) always return their primary endpoint untouched.
	 */
	suspend fun resolve(
		connection: ChannelFlowConnection,
		mode: ChannelFlowEndpointMode,
		serverId: String,
	): ChannelFlowEndpoint {
		val primary = ChannelFlowEndpoint(connection.baseUrl, connection.m3uUrl, connection.epgUrl)
		val local = connection.localEndpoint
		val public = connection.publicEndpoint
		if (local == null && public == null) return publish(serverId, primary, connection.routeOf(primary))

		when (mode) {
			ChannelFlowEndpointMode.LOCAL -> if (local != null) {
				return publish(serverId, local, ChannelFlowRoute.LOCAL)
			}
			ChannelFlowEndpointMode.PUBLIC -> if (public != null) {
				return publish(serverId, public, ChannelFlowRoute.PUBLIC)
			}
			ChannelFlowEndpointMode.AUTO -> return resolveAutomatic(connection, primary, local, public, serverId)
		}
		return publish(serverId, primary, connection.routeOf(primary))
	}

	/**
	 * Runs [block] on the resolved endpoint and, when the block fails because the route itself
	 * is unreachable, retries once on the other endpoint. The successful route is remembered so
	 * the next call does not go back to the dead one.
	 */
	suspend fun <T> withFailover(
		connection: ChannelFlowConnection,
		mode: ChannelFlowEndpointMode,
		serverId: String,
		block: suspend (ChannelFlowEndpoint) -> T,
	): T {
		val active = resolve(connection, mode, serverId)
		try {
			return block(active)
		} catch (error: IOException) {
			val fallback = fallbackFor(connection, active) ?: throw error
			Timber.w(error, "ChannelFlow endpoint %s failed; retrying on %s", active.baseUrl, fallback.first.baseUrl)
			remember(serverId, connection, fallback.second)
			try {
				return block(fallback.first)
			} catch (retryError: IOException) {
				// Both routes failed: drop the cached choice so the next call probes again.
				forget(serverId, connection)
				throw retryError
			}
		}
	}

	/** The published route for [serverId], without probing anything. */
	fun peekActive(serverId: String): ChannelFlowActiveRoute? =
		_active.value?.takeIf { it.serverId == serverId }

	/** Forgets every cached decision, for example after the endpoint mode was changed. */
	fun invalidate() {
		cache.clear()
		_active.value = null
	}

	private suspend fun resolveAutomatic(
		connection: ChannelFlowConnection,
		primary: ChannelFlowEndpoint,
		local: ChannelFlowEndpoint?,
		public: ChannelFlowEndpoint?,
		serverId: String,
	): ChannelFlowEndpoint {
		if (local == null) return publish(serverId, primary, connection.routeOf(primary))
		// Only one variant was handed out: use it, there is nothing to fall back to.
		if (public == null) return publish(serverId, local, ChannelFlowRoute.LOCAL)

		val key = cacheKey(serverId, local.baseUrl)
		// The guide fetches the playlist and the listings at the same time; one probe is enough.
		return probeLock.withLock {
			cache.read(key)?.takeIf { fresh(it) }?.let { entry ->
				return@withLock publish(serverId, endpointFor(entry.route, local, public), entry.route)
			}

			val reachable = probeLocal(local.baseUrl)
			val entry = ChannelFlowRouteCacheEntry(
				route = if (reachable) ChannelFlowRoute.LOCAL else ChannelFlowRoute.PUBLIC,
				at = clock(),
				localReachable = reachable,
			)
			cache.write(key, entry)
			Timber.i("ChannelFlow local endpoint %s reachable=%s", local.baseUrl, reachable)
			publish(serverId, endpointFor(entry.route, local, public), entry.route)
		}
	}

	private suspend fun fallbackFor(
		connection: ChannelFlowConnection,
		active: ChannelFlowEndpoint,
	): Pair<ChannelFlowEndpoint, ChannelFlowRoute>? {
		val local = connection.localEndpoint ?: return null
		val public = connection.publicEndpoint ?: return null
		if (local.baseUrl.equals(public.baseUrl, ignoreCase = true)) return null
		if (!active.baseUrl.equals(local.baseUrl, ignoreCase = true)) {
			// On the public (or primary) endpoint: only move to the LAN once it answers.
			val reachable = probeLock.withLock { probeLocal(local.baseUrl) }
			return if (reachable) local to ChannelFlowRoute.LOCAL else null
		}
		return public to ChannelFlowRoute.PUBLIC
	}

	private fun remember(serverId: String, connection: ChannelFlowConnection, route: ChannelFlowRoute) {
		val local = connection.localEndpoint
		if (local != null) {
			val entry = ChannelFlowRouteCacheEntry(route, clock(), localReachable = route == ChannelFlowRoute.LOCAL)
			cache.write(cacheKey(serverId, local.baseUrl), entry)
		}
		_active.value = ChannelFlowActiveRoute(serverId, route)
	}

	private fun forget(serverId: String, connection: ChannelFlowConnection) {
		connection.localEndpoint?.let { cache.remove(cacheKey(serverId, it.baseUrl)) }
		if (_active.value?.serverId == serverId) _active.value = null
	}

	private fun publish(serverId: String, endpoint: ChannelFlowEndpoint, route: ChannelFlowRoute?): ChannelFlowEndpoint {
		val next = route?.let { ChannelFlowActiveRoute(serverId, it) }
		if (_active.value != next) _active.value = next
		return endpoint
	}

	private fun endpointFor(
		route: ChannelFlowRoute,
		local: ChannelFlowEndpoint,
		public: ChannelFlowEndpoint,
	): ChannelFlowEndpoint = if (route == ChannelFlowRoute.LOCAL) local else public

	private fun fresh(entry: ChannelFlowRouteCacheEntry): Boolean {
		val ttl = if (entry.localReachable) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
		val age = clock() - entry.at
		return age >= 0 && age < ttl
	}

	private fun watchNetwork(app: Context) {
		val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
		val request = NetworkRequest.Builder()
			.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
			.build()
		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) = onNetworkEvent()
			override fun onLost(network: Network) = onNetworkEvent()

			private fun onNetworkEvent() {
				// The callback reports the network that is already connected on registration;
				// only a real change should throw away a cached decision.
				if (firstEvent.compareAndSet(true, false)) return
				invalidate()
			}
		}
		runCatching { connectivity.registerNetworkCallback(request, callback) }
			.onFailure { Timber.w(it, "Unable to watch network changes for ChannelFlow endpoints") }
	}

	companion object {
		private const val POSITIVE_TTL_MS = 30_000L
		private const val NEGATIVE_TTL_MS = 10_000L
		private val probeHttp = OkHttpClient.Builder()
			.connectTimeout(2, TimeUnit.SECONDS)
			.readTimeout(2, TimeUnit.SECONDS)
			.writeTimeout(2, TimeUnit.SECONDS)
			.build()

		private fun cacheKey(serverId: String, localBaseUrl: String): String = "$serverId|$localBaseUrl"

		/**
		 * Cheap reachability check against the server's anonymous `GET /health`. A local URL on
		 * a different network fails fast, which is exactly what tells the app to go public.
		 */
		private suspend fun probeHealth(baseUrl: String): Boolean {
			val url = baseUrl.trim().trimEnd('/')
			if (url.isBlank()) return false
			return withContext(Dispatchers.IO) {
				runCatching {
					val request = Request.Builder()
						.url("$url/health")
						.header("Accept", "text/plain, */*")
						.get()
						.build()
					probeHttp.newCall(request).execute().use { response -> response.isSuccessful }
				}.getOrDefault(false)
			}
		}
	}
}

/** SharedPreferences backed [ChannelFlowRouteCache]. */
class ChannelFlowRoutePreferences(context: Context) : ChannelFlowRouteCache {
	private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	override fun read(key: String): ChannelFlowRouteCacheEntry? {
		val raw = prefs.getString(storeKey(key), null) ?: return null
		val parts = raw.split(SEPARATOR)
		if (parts.size != CACHE_FIELDS) return null
		val route = runCatching { ChannelFlowRoute.valueOf(parts[0]) }.getOrNull() ?: return null
		val at = parts[1].toLongOrNull() ?: return null
		return ChannelFlowRouteCacheEntry(route, at, parts[2].toBoolean())
	}

	override fun write(key: String, entry: ChannelFlowRouteCacheEntry) {
		val value = "${entry.route.name}$SEPARATOR${entry.at}$SEPARATOR${entry.localReachable}"
		prefs.edit().putString(storeKey(key), value).apply()
	}

	override fun remove(key: String) {
		prefs.edit().remove(storeKey(key)).apply()
	}

	override fun clear() {
		prefs.edit().clear().apply()
	}

	private fun storeKey(key: String): String = key.replace(WHITESPACE_OR_SEPARATOR, "_")

	companion object {
		private const val PREFS = "channelflow_endpoints"
		private const val SEPARATOR = ";"
		private const val CACHE_FIELDS = 3
		private val WHITESPACE_OR_SEPARATOR = Regex("""[\s|]""")
	}
}

/**
 * Runs [block] against the endpoint [serverId] should use — the active server unless another
 * id is given (revoking a server being removed) — with a one shot route failover.
 */
suspend fun <T> ChannelFlowEndpointResolver.withActiveFailover(
	store: ChannelFlowConnectionStore,
	connection: ChannelFlowConnection,
	serverId: String? = null,
	block: suspend (ChannelFlowEndpoint) -> T,
): T {
	val id = serverId ?: store.activeServerId.orEmpty()
	val mode = store.state.value.servers.firstOrNull { it.id == id }?.endpointMode
		?: ChannelFlowEndpointMode.AUTO
	return withFailover(connection, mode, id, block)
}
