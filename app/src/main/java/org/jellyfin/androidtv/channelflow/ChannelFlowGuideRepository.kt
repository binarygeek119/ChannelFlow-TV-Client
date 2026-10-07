package org.jellyfin.androidtv.channelflow

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ChannelType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class ChannelFlowGuideRepository(
	private val store: ChannelFlowConnectionStore,
	private val liveTvPreferences: LiveTvPreferences,
	private val access: Lazy<ChannelFlowAccessGuard>,
	private val resolver: ChannelFlowEndpointResolver,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private val loadLock = Mutex()
	private var loadJob: Job? = null
	private val channelsReady = MutableStateFlow(false)
	private val programsReady = MutableStateFlow(false)
	private var channels: List<ChannelFlowChannel> = emptyList()
	private var programs: List<ChannelFlowProgram> = emptyList()
	private var loadedAt: Long = 0L
	@Volatile
	private var loadedRoute: ChannelFlowActiveRoute? = null
	@Volatile
	private var loading = false
	private val http = OkHttpClient.Builder()
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(3, TimeUnit.MINUTES)
		.writeTimeout(30, TimeUnit.SECONDS)
		.followRedirects(true)
		.followSslRedirects(true)
		.build()

	init {
		// The local and public URL carry different absolute stream links, so a route change
		// means the guide has to be fetched again from the endpoint now in use.
		scope.launch {
			resolver.active.collect { choice ->
				if (choice == null || loading) return@collect
				if (choice.serverId != store.activeServerId) return@collect
				if (choice == loadedRoute) return@collect
				Timber.i("ChannelFlow endpoint changed to %s; reloading the guide", choice.route)
				clear()
				prefetchLatest()
			}
		}
	}

	fun prefetchLatest() {
		if (!store.isConnected) return
		scope.launch {
			runCatching { ensureLoadStarted(force = false).join() }
				.onFailure { Timber.w(it, "Unable to refresh ChannelFlow guide") }
		}
	}

	suspend fun refresh(force: Boolean = false) {
		ensureLoadStarted(force).join()
	}

	fun findChannel(itemId: UUID): ChannelFlowChannel? {
		channels.firstOrNull { it.id == itemId }?.let { return it }
		val program = programs.firstOrNull { it.id == itemId } ?: return null
		return channels.firstOrNull { it.id == program.channelId }
	}

	fun getStreamUrl(itemId: UUID): String? {
		channels.firstOrNull { it.id == itemId }?.streamUrl?.let { return it }
		val program = programs.firstOrNull { it.id == itemId } ?: return null
		return channels.firstOrNull { it.id == program.channelId }?.streamUrl
	}

	suspend fun awaitStreamUrl(itemId: UUID): String? {
		awaitChannels()
		return mutex.withLock { getStreamUrl(itemId) }
	}

	fun getLogoUrl(itemId: UUID): String? {
		channels.firstOrNull { it.id == itemId }?.logoUrl?.let { return it }
		return programs.firstOrNull { it.id == itemId }?.iconUrl
	}

	suspend fun effectiveNow(): LocalDateTime {
		awaitPrograms()
		return mutex.withLock { ChannelFlowGuideClock.now() }
	}

	suspend fun getChannels(): List<BaseItemDto> {
		awaitChannels()
		awaitPrograms()
		val now = mutex.withLock { ChannelFlowGuideClock.now() }
		val favsAtTop = liveTvPreferences[LiveTvPreferences.favsAtTop]
		return mutex.withLock {
			channels
				.filter { it.hasPlayableStream() }
				.map { it.toBaseItem(now) }
				.sortedWith(
					compareByDescending<BaseItemDto> { favsAtTop && it.userData?.isFavorite == true }
						.thenBy { ChannelNumber.parse(it.number) ?: ChannelNumber(Int.MAX_VALUE, 0) }
						.thenBy { it.name.orEmpty() }
				)
		}
	}

	suspend fun getChannel(id: UUID): BaseItemDto? {
		awaitChannels()
		awaitPrograms()
		return mutex.withLock {
			channels.firstOrNull { it.id == id && it.hasPlayableStream() }?.toBaseItem(ChannelFlowGuideClock.now())
		}
	}

	suspend fun getPrograms(
		channelIds: Collection<UUID>,
		startTime: LocalDateTime,
		endTime: LocalDateTime,
	): List<BaseItemDto> {
		awaitPrograms()
		val ids = channelIds.filterNotNull().toSet()
		if (ids.isEmpty()) return emptyList()
		return mutex.withLock {
			val matched = programs
				.filter { it.channelId in ids && it.start < endTime && it.end > startTime }
				.sortedBy { it.start }
			Timber.d("XMLTV matched ${matched.size} of ${programs.size} programmes for ${ids.size} channels window=$startTime..$endTime")
			matched.map { it.toBaseItem() }
		}
	}

	suspend fun getProgram(id: UUID): BaseItemDto? {
		awaitPrograms()
		return mutex.withLock {
			programs.firstOrNull { it.id == id }?.toBaseItem()
		}
	}

	fun clear() {
		loadJob?.cancel()
		loadJob = null
		channels = emptyList()
		programs = emptyList()
		loadedAt = 0L
		loadedRoute = null
		ChannelFlowGuideClock.updateCoverage(emptyList())
		channelsReady.value = false
		programsReady.value = false
	}

	private suspend fun awaitChannels() {
		if (store.connection == null) {
			clear()
			return
		}
		ensureLoadStarted()
		channelsReady.first { it }
	}

	private suspend fun awaitPrograms() {
		if (store.connection == null) {
			clear()
			return
		}
		ensureLoadStarted()
		programsReady.first { it }
	}

	private suspend fun ensureLoadStarted(force: Boolean = false): Job {
		val connection = store.connection ?: run {
			clear()
			return completedJob()
		}

		if (!force && isFresh()) {
			if (!channelsReady.value) channelsReady.value = true
			if (!programsReady.value) programsReady.value = true
			return loadJob?.takeIf { it.isActive } ?: completedJob()
		}

		return loadLock.withLock {
			if (!force && isFresh()) {
				return@withLock loadJob?.takeIf { it.isActive } ?: completedJob()
			}
			if (force) loadJob?.cancel()
			loadJob?.takeIf { it.isActive } ?: startLoad(connection)
		}
	}

	private fun startLoad(connection: ChannelFlowConnection): Job {
		channelsReady.value = false
		programsReady.value = false
		loading = true
		return scope.launch {
			try {
				load(connection)
			} finally {
				loading = false
			}
		}.also { loadJob = it }
	}

	private suspend fun load(connection: ChannelFlowConnection) {
		supervisorScope {
			launch {
				try {
					val nextChannels = runCatching {
						resolver.withActiveFailover(store, connection) { active ->
							val playlist = fetchText(connection, active.m3uUrl)
							val parsed = M3uParser.parse(playlist).map { channel -> channel.rebased(connection, active) }
							// Remember which endpoint produced these links, not just the one
							// that happens to be active once the load is over.
							loadedRoute = connection.routeOf(active)
								?.let { ChannelFlowActiveRoute(store.activeServerId.orEmpty(), it) }
							parsed
						}
					}
						.onFailure { Timber.w(it, "Unable to load ChannelFlow M3U") }
						.getOrDefault(emptyList())
					mutex.withLock { channels = nextChannels }
					Timber.i("Loaded ${nextChannels.size} ChannelFlow channels")
				} finally {
					channelsReady.value = true
				}
			}
			launch {
				try {
					val nextPrograms = runCatching {
						resolver.withActiveFailover(store, connection) { active ->
							val listings = fetchText(connection, active.epgUrl)
							XmltvParser.parse(listings).map { program -> program.rebased(connection, active) }
						}
					}
						.onFailure { Timber.w(it, "Unable to load ChannelFlow XMLTV") }
						.getOrNull()
					if (nextPrograms != null) {
						mutex.withLock {
							programs = nextPrograms
							loadedAt = System.currentTimeMillis()
							ChannelFlowGuideClock.updateCoverage(nextPrograms)
						}
						val deviceNow = LocalDateTime.now()
						val guideNow = ChannelFlowGuideClock.now()
						if (guideNow != deviceNow) {
							Timber.w("Device clock $deviceNow is outside XMLTV coverage; guide using $guideNow")
						}
						Timber.i("Loaded ${nextPrograms.size} ChannelFlow XMLTV programmes")
					} else {
						Timber.w("ChannelFlow XMLTV fetch/parse failed; guide listings not updated")
					}
				} finally {
					programsReady.value = true
				}
			}
		}
	}

	private fun isFresh(): Boolean {
		val stale = System.currentTimeMillis() - loadedAt > CACHE_TTL_MS
		return !stale && channels.isNotEmpty() && programsReady.value && loadedAt > 0L && routeStillActive()
	}

	/**
	 * False when the guide was fetched from an endpoint the app no longer uses, for example
	 * after leaving the house and failing over from the local URL to the public one.
	 */
	private fun routeStillActive(): Boolean {
		val serverId = store.activeServerId ?: return true
		val current = loadedRoute ?: return resolver.peekActive(serverId) == null
		return current == resolver.peekActive(serverId)
	}

	private fun ChannelFlowChannel.toBaseItem(now: LocalDateTime): BaseItemDto {
		val current = programs.firstOrNull { it.channelId == id && it.start <= now && it.end > now }
		return BaseItemDto(
			id = id,
			name = name,
			number = number,
			type = BaseItemKind.TV_CHANNEL,
			mediaType = MediaType.VIDEO,
			channelType = ChannelType.TV,
			locationType = LocationType.REMOTE,
			imageTags = logoUrl?.let { mapOf(ImageType.PRIMARY to it, ImageType.LOGO to it) },
			primaryImageAspectRatio = 1.0,
			currentProgram = current?.toBaseItem(),
			userData = userData(id),
		)
	}

	private fun ChannelFlowProgram.toBaseItem(): BaseItemDto = BaseItemDto(
		id = id,
		name = title,
		episodeTitle = episodeTitle,
		overview = overview,
		type = BaseItemKind.PROGRAM,
		mediaType = MediaType.VIDEO,
		channelId = channelId,
		parentId = channelId,
		channelName = channels.firstOrNull { it.id == channelId }?.name,
		channelNumber = channels.firstOrNull { it.id == channelId }?.number,
		startDate = start,
		endDate = end,
		premiereDate = start,
		officialRating = officialRating,
		productionYear = productionYear,
		genres = categories.takeIf { it.isNotEmpty() },
		imageTags = iconUrl?.let { mapOf(ImageType.PRIMARY to it, ImageType.THUMB to it) },
		runTimeTicks = java.time.Duration.between(start, end).seconds.coerceAtLeast(0) * 10_000_000,
	)

	private fun userData(channelId: UUID) = UserItemDataDto(
		playbackPositionTicks = 0,
		playCount = 0,
		isFavorite = store.isFavorite(channelId),
		played = false,
		key = channelId.toString(),
		itemId = channelId,
	)

	private suspend fun fetchText(connection: ChannelFlowConnection, url: String): String = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(withApiKey(url, connection.apiKey))
			.header("Accept", "application/xml, text/xml, audio/x-mpegurl, */*")
			.header("Accept-Encoding", "gzip")
			.apply {
				if (connection.apiKey.isNotBlank()) header("X-Api-Key", connection.apiKey)
			}
			.get()
			.build()
		http.newCall(request).execute().use { response ->
			if (response.code == 401 || response.code == 403) {
				access.value.forgetUnauthorized(connection)
				error("HTTP ${response.code} unauthorized for ${redact(url)}")
			}
			if (!response.isSuccessful) error("HTTP ${response.code} for ${redact(url)}")
			val bytes = response.body?.bytes() ?: ByteArray(0)
			decodeBody(bytes)
		}
	}

	private fun decodeBody(bytes: ByteArray): String {
		if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
			return GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
		}
		return bytes.toString(Charsets.UTF_8)
	}

	companion object {
		private const val CACHE_TTL_MS = 2 * 60 * 1000L

		private fun completedJob(): Job = Job().apply { complete() }

		private fun withApiKey(url: String, apiKey: String): String {
			if (apiKey.isBlank() || url.contains("apiKey=", ignoreCase = true)) return url
			val uri = Uri.parse(url).buildUpon().appendQueryParameter("apiKey", apiKey).build()
			return uri.toString()
		}

		private fun redact(url: String): String = url.replace(Regex("apiKey=[^&]*", RegexOption.IGNORE_CASE), "apiKey=***")
	}
}

/** Rewrites playlist links so they point at the endpoint the app is actually using. */
private fun ChannelFlowChannel.rebased(
	connection: ChannelFlowConnection,
	active: ChannelFlowEndpoint,
): ChannelFlowChannel = copy(
	streamUrl = connection.remapUrl(streamUrl, active.baseUrl),
	logoUrl = logoUrl?.let { connection.remapUrl(it, active.baseUrl) },
)

/** Rewrites listing artwork links the same way as the playlist links. */
private fun ChannelFlowProgram.rebased(
	connection: ChannelFlowConnection,
	active: ChannelFlowEndpoint,
): ChannelFlowProgram = copy(
	iconUrl = iconUrl?.let { connection.remapUrl(it, active.baseUrl) },
)
