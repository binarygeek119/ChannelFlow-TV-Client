package org.jellyfin.androidtv.channelflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChannelFlowPairClient {
	companion object {
		const val PIN_SERVER_URL = "https://channelflow.duckdns.org"
	}

	private val json = Json { ignoreUnknownKeys = true }

	private val http = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(11, TimeUnit.MINUTES)
		.writeTimeout(15, TimeUnit.SECONDS)
		.pingInterval(15, TimeUnit.SECONDS)
		.build()

	fun pinServerUrl(): String = PIN_SERVER_URL.trimEnd('/')

	suspend fun waitForConnection(
		onPin: (String) -> Unit,
	): Result<ChannelFlowConnection> = withContext(Dispatchers.IO) {
		runCatching {
			try {
				waitOnWebSocket(onPin)
			} catch (error: ChannelFlowPairException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "Pin server websocket failed; trying long-poll")
				waitOnLongPoll(onPin)
			}
		}.onFailure { error ->
			Timber.w(error, "ChannelFlow pin wait failed")
		}
	}

	private suspend fun waitOnWebSocket(onPin: (String) -> Unit): ChannelFlowConnection {
		val url = pinServerUrl().replace(Regex("^http"), "ws") + "/v1/wait"
		val request = Request.Builder().url(url).build()
		return suspendCancellableCoroutine { continuation ->
			val socket = http.newWebSocket(request, object : WebSocketListener() {
				private var pin: String? = null

				override fun onMessage(webSocket: WebSocket, text: String) {
					handleMessage(text, onPin, { pin }) { pin = it }
						?.let { complete(webSocket, it) }
				}

				override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
					if (continuation.isActive) {
						continuation.resumeWithException(t)
					}
				}

				override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
					if (continuation.isActive) {
						continuation.resumeWithException(
							ChannelFlowPairException(ChannelFlowPairException.Kind.FAILED)
						)
					}
				}

				private fun complete(webSocket: WebSocket, result: Result<ChannelFlowConnection>) {
					if (!continuation.isActive) return
					result.fold(
						onSuccess = { continuation.resume(it) },
						onFailure = { continuation.resumeWithException(it) },
					)
					webSocket.close(1000, null)
				}
			})
			continuation.invokeOnCancellation { socket.cancel() }
		}
	}

	private suspend fun waitOnLongPoll(onPin: (String) -> Unit): ChannelFlowConnection {
		val issue = Request.Builder()
			.url("${pinServerUrl()}/v1/wait")
			.post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
			.build()
		val issuedPin = http.newCall(issue).execute().use { response ->
			if (!response.isSuccessful) {
				error("Pin server returned HTTP ${response.code}")
			}
			val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
			obj["pin"]?.jsonPrimitive?.contentOrNull
				?: error("pin server did not issue a pin")
		}
		val pin = ChannelFlowPinCrypto.normalize(issuedPin)
		onPin(issuedPin)
		while (true) {
			val poll = Request.Builder()
				.url("${pinServerUrl()}/v1/wait?pin=$pin")
				.get()
				.build()
			val handled = http.newCall(poll).execute().use { response ->
				if (response.code == 404) {
					throw ChannelFlowPairException(ChannelFlowPairException.Kind.EXPIRED)
				}
				if (!response.isSuccessful) {
					error("Pin server returned HTTP ${response.code}")
				}
				handleMessage(response.body?.string().orEmpty(), onPin, { pin }) {}
			}
			if (handled == null) {
				continue
			}
			return handled.getOrThrow()
		}
	}

	private fun handleMessage(
		text: String,
		onPin: (String) -> Unit,
		currentPin: () -> String?,
		setPin: (String) -> Unit,
	): Result<ChannelFlowConnection>? {
		val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
		val type = obj["type"]?.jsonPrimitive?.contentOrNull
		val issued = obj["pin"]?.jsonPrimitive?.contentOrNull
		if (!issued.isNullOrBlank() && currentPin() == null) {
			setPin(ChannelFlowPinCrypto.normalize(issued))
			onPin(issued)
			return null
		}
		return when (type) {
			"payload" -> {
				val pin = currentPin() ?: return Result.failure(
					ChannelFlowPairException(ChannelFlowPairException.Kind.FAILED)
				)
				val ciphertext = obj["ciphertext"]?.jsonPrimitive?.contentOrNull.orEmpty()
				runCatching { connectionFromCiphertext(pin, ciphertext) }
			}
			"expired" -> Result.failure(ChannelFlowPairException(ChannelFlowPairException.Kind.EXPIRED))
			else -> null
		}
	}

	private fun connectionFromCiphertext(pin: String, ciphertext: String): ChannelFlowConnection {
		val payload = ChannelFlowPinCrypto.decrypt(pin, ciphertext)
		val primary = endpoint(payload.m3u, payload.xmltv)
			?: endpoint(payload.m3uPublic, payload.xmltvPublic)
			?: endpoint(payload.m3uLocal, payload.xmltvLocal)
			?: error("Pin payload did not include M3U and XMLTV URLs")
		val apiKey = ChannelFlowUrls.extractApiKey(primary.m3uUrl)
			.ifBlank { ChannelFlowUrls.extractApiKey(primary.epgUrl) }
		return ChannelFlowConnection(
			baseUrl = primary.baseUrl,
			m3uUrl = primary.m3uUrl,
			epgUrl = primary.epgUrl,
			apiKey = apiKey,
			publicEndpoint = endpoint(payload.m3uPublic, payload.xmltvPublic),
			localEndpoint = endpoint(payload.m3uLocal, payload.xmltvLocal),
		)
	}

	private fun endpoint(m3u: String?, xmltv: String?): ChannelFlowEndpoint? {
		val playlist = m3u?.trim().orEmpty()
		val listings = xmltv?.trim().orEmpty()
		if (playlist.isBlank() || listings.isBlank()) return null
		return ChannelFlowEndpoint(
			baseUrl = ChannelFlowUrls.baseUrlFromLiveTvUrl(playlist),
			m3uUrl = playlist,
			epgUrl = listings,
		)
	}
}

class ChannelFlowPairException(
	val kind: Kind,
	val serverMessage: String? = null,
) : Exception(serverMessage ?: kind.name) {
	enum class Kind { INVALID, EXPIRED, FAILED }
}
