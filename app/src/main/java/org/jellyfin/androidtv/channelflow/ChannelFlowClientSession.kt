package org.jellyfin.androidtv.channelflow

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

class ChannelFlowClientSession(
	context: Context,
	private val store: ChannelFlowConnectionStore,
	private val access: ChannelFlowAccessGuard,
	private val catalog: ChannelFlowGuideRepository,
	private val resolver: ChannelFlowEndpointResolver,
) {
	private val app = context.applicationContext
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
	private val http = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(15, TimeUnit.SECONDS)
		.writeTimeout(15, TimeUnit.SECONDS)
		.build()
	private var started = false

	fun start() {
		if (started) return
		started = true
		scope.launch {
			var lastKey: String? = null
			store.state.collect { state ->
				val key = state.connection?.apiKey
				if (key == lastKey) return@collect
				lastKey = key
				runCatching { refresh() }
			}
		}
		scope.launch {
			while (isActive) {
				delay(HEARTBEAT_MS)
				runCatching { refresh() }
			}
		}
	}

	suspend fun refresh() {
		val connection = store.connection ?: return
		if (connection.apiKey.isBlank() || connection.baseUrl.isBlank()) return
		val result = runCatching {
			resolver.withActiveFailover(store, connection) { active -> postSession(connection, active) }
		}
			.onFailure { error -> Timber.w(error, "ChannelFlow session heartbeat failed") }
			.getOrNull() ?: return
		when (result) {
			is SessionOutcome.Rejected -> access.forgetUnauthorized(connection)
			is SessionOutcome.Updated -> {
				if (result.apiKey.isNotBlank() && result.apiKey != connection.apiKey) {
					store.save(connection.withApiKey(result.apiKey))
					catalog.clear()
					catalog.prefetchLatest()
					Timber.i("ChannelFlow TV received a unique API key from the server")
				}
			}
			SessionOutcome.Ignored -> Unit
		}
	}

	suspend fun revoke(serverId: String, connection: ChannelFlowConnection) {
		if (connection.apiKey.isBlank() || connection.baseUrl.isBlank()) return
		runCatching {
			withContext(Dispatchers.IO) {
				resolver.withActiveFailover(store, connection, serverId) { active ->
					val request = Request.Builder()
						.url(ChannelFlowUrls.revokeUrl(active.baseUrl))
						.header("X-Api-Key", connection.apiKey)
						.header("Accept", "application/json")
						.delete()
						.build()
					http.newCall(request).execute().close()
				}
			}
		}
	}

	private suspend fun postSession(
		connection: ChannelFlowConnection,
		active: ChannelFlowEndpoint,
	): SessionOutcome =
		withContext(Dispatchers.IO) {
			val payload = json.encodeToString(
				SessionRequest.serializer(),
				SessionRequest(
					deviceId = ChannelFlowDevice.id(app),
					deviceName = ChannelFlowDevice.name(app),
					appVersion = ChannelFlowDevice.appVersion(),
					osVersion = ChannelFlowDevice.osVersion(),
				),
			)
			val request = Request.Builder()
				.url(ChannelFlowUrls.sessionUrl(active.baseUrl))
				.header("X-Api-Key", connection.apiKey)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.post(payload.toRequestBody(JSON))
				.build()
			http.newCall(request).execute().use { response ->
				when {
					response.code == 401 || response.code == 403 -> SessionOutcome.Rejected
					!response.isSuccessful -> SessionOutcome.Ignored
					else -> {
						val body = response.body?.string().orEmpty()
						val parsed = runCatching {
							json.decodeFromString(SessionResponse.serializer(), body)
						}.getOrNull()
						SessionOutcome.Updated(parsed?.apiKey.orEmpty())
					}
				}
			}
		}

	private sealed class SessionOutcome {
		data object Rejected : SessionOutcome()
		data object Ignored : SessionOutcome()
		data class Updated(val apiKey: String) : SessionOutcome()
	}

	@Serializable
	private data class SessionRequest(
		val deviceId: String,
		val deviceName: String? = null,
		val appVersion: String? = null,
		val osVersion: String? = null,
	)

	@Serializable
	private data class SessionResponse(
		val clientId: String? = null,
		val apiKey: String? = null,
		val deviceId: String? = null,
	)

	companion object {
		private const val HEARTBEAT_MS = 60_000L
		private val JSON = "application/json; charset=utf-8".toMediaType()
	}
}
