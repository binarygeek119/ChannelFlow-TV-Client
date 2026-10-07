package org.jellyfin.androidtv.channelflow

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.TimeUnit

class ChannelFlowLogShipper(
	context: Context,
	private val store: ChannelFlowConnectionStore,
	private val access: ChannelFlowAccessGuard,
	private val resolver: ChannelFlowEndpointResolver,
) {
	private val app = context.applicationContext
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val queueLock = Any()
	private val queue = ArrayDeque<ChannelFlowLogEntry>()
	private val json = Json { encodeDefaults = false }
	private val http = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.writeTimeout(20, TimeUnit.SECONDS)
		.build()
	private val tree = RemoteTree()
	private var started = false
	private var dropUntilReconnect = false
	private var lastApiKey: String? = null

	fun start() {
		if (started) return
		started = true
		if (Timber.forest().none { it === tree }) {
			Timber.plant(tree)
		}
		wrapUncaughtExceptions()
		ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStop(owner: LifecycleOwner) {
				scope.launch { flush() }
			}
		})
		scope.launch {
			while (isActive) {
				flush()
				delay(FLUSH_INTERVAL_MS)
			}
		}
		Timber.i("ChannelFlow remote log shipper started")
	}

	fun enqueue(priority: Int, tag: String?, message: String, throwable: Throwable?) {
		val entry = ChannelFlowLogEntry(
			timestamp = Instant.now().toString(),
			level = ChannelFlowClientLogs.levelName(priority),
			tag = ChannelFlowClientLogs.sanitizeText(tag, 80),
			message = ChannelFlowClientLogs.sanitizeText(message, ChannelFlowClientLogs.MAX_MESSAGE).orEmpty(),
			exception = ChannelFlowClientLogs.sanitizeText(throwable?.stackTraceToString(), ChannelFlowClientLogs.MAX_EXCEPTION),
		)
		synchronized(queueLock) {
			while (queue.size >= ChannelFlowClientLogs.MAX_QUEUE) {
				queue.removeFirst()
			}
			queue.addLast(entry)
		}
	}

	private suspend fun flush() {
		val connection = store.connection ?: return
		if (connection.apiKey.isBlank() || connection.baseUrl.isBlank()) return
		if (connection.apiKey != lastApiKey) {
			lastApiKey = connection.apiKey
			dropUntilReconnect = false
		}
		if (dropUntilReconnect) return

		val batch = synchronized(queueLock) {
			if (queue.isEmpty()) emptyList()
			else {
				val count = minOf(queue.size, ChannelFlowClientLogs.MAX_BATCH)
				ArrayList<ChannelFlowLogEntry>(count).also { taken ->
					repeat(count) { taken.add(queue.removeFirst()) }
				}
			}
		}
		if (batch.isEmpty()) return

		val ok = runCatching {
			resolver.withActiveFailover(store, connection) { active -> post(connection, active, batch) }
		}.getOrElse { false }
		if (!ok) {
			synchronized(queueLock) {
				for (i in batch.indices.reversed()) {
					if (queue.size >= ChannelFlowClientLogs.MAX_QUEUE) break
					queue.addFirst(batch[i])
				}
			}
		}
	}

	private suspend fun post(
		connection: ChannelFlowConnection,
		active: ChannelFlowEndpoint,
		batch: List<ChannelFlowLogEntry>,
	): Boolean =
		withContext(Dispatchers.IO) {
			val payload = ChannelFlowLogBatch(
				deviceId = ChannelFlowDevice.id(app),
				deviceName = ChannelFlowDevice.name(app),
				appVersion = ChannelFlowDevice.appVersion(),
				osVersion = ChannelFlowDevice.osVersion(),
				entries = batch,
			)
			val body = json.encodeToString(ChannelFlowLogBatch.serializer(), payload)
				.toRequestBody(JSON)
			val request = Request.Builder()
				.url(ChannelFlowClientLogs.ingestUrl(active.baseUrl))
				.header("X-Api-Key", connection.apiKey)
				.header("Accept", "application/json")
				.header("User-Agent", USER_AGENT)
				.post(body)
				.build()
			http.newCall(request).execute().use { response ->
				when {
					response.isSuccessful -> true
					response.code == 401 || response.code == 403 -> {
						dropUntilReconnect = true
						access.forgetUnauthorized(connection)
						true
					}
					response.code in 400..499 -> true
					else -> false
				}
			}
		}

	private fun wrapUncaughtExceptions() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			enqueue(
				priority = android.util.Log.ERROR,
				tag = "Uncaught",
				message = "Uncaught exception on ${thread.name}",
				throwable = error,
			)
			runCatching {
				runBlocking(Dispatchers.IO) {
					withTimeoutOrNull(2_000) { flush() }
				}
			}
			previous?.uncaughtException(thread, error)
		}
	}

	private inner class RemoteTree : Timber.Tree() {
		override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
			if (tag == TAG) return
			enqueue(priority, tag, message, t)
		}
	}

	companion object {
		const val TAG = "ChannelFlowLogShipper"
		private const val FLUSH_INTERVAL_MS = 4_000L
		private val JSON = "application/json; charset=utf-8".toMediaType()
		private val USER_AGENT =
			"ChannelFlow-TV/${BuildConfig.VERSION_NAME} (+https://github.com/binarygeek119/ChannelFlow-TV-Client)"
	}
}

@Serializable
internal data class ChannelFlowLogBatch(
	val deviceId: String,
	val deviceName: String? = null,
	val appVersion: String? = null,
	val osVersion: String? = null,
	val entries: List<ChannelFlowLogEntry>,
)

@Serializable
internal data class ChannelFlowLogEntry(
	val timestamp: String,
	val level: String,
	val tag: String? = null,
	val message: String,
	val exception: String? = null,
)
