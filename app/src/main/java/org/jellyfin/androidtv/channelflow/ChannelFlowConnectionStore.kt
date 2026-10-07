package org.jellyfin.androidtv.channelflow

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.File
import java.util.UUID

class ChannelFlowConnectionStore(
	context: Context,
) {
	private val app = context.applicationContext
	private val file = app.filesDir.resolve(FILE_NAME)
	private val deviceFile = deviceProtectedContext(app).filesDir.resolve(FILE_NAME)
	private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	private val _state = MutableStateFlow(load())
	val state: StateFlow<ChannelFlowServersState> = _state.asStateFlow()

	val connection: ChannelFlowConnection?
		get() = _state.value.connection

	val isConnected: Boolean
		get() = connection != null

	val servers: List<ChannelFlowSavedServer>
		get() = _state.value.servers

	val activeServerId: String?
		get() = _state.value.activeServerId

	init {
		if (isConnected) write()
	}

	fun save(connection: ChannelFlowConnection) {
		val current = _state.value
		val existing = current.servers.firstOrNull { it.connection.baseUrl.equals(connection.baseUrl, ignoreCase = true) }
		val server = existing?.copy(connection = connection)
			?: ChannelFlowSavedServer(id = UUID.randomUUID().toString(), connection = connection)
		val servers = current.servers.filterNot { it.id == server.id } + server
		commit(
			current.copy(
				servers = servers,
				activeServerId = server.id,
				connection = server.connection,
			)
		)
	}

	fun setActive(serverId: String): Boolean {
		val server = _state.value.servers.firstOrNull { it.id == serverId } ?: return false
		commit(
			_state.value.copy(
				activeServerId = server.id,
				connection = server.connection,
			)
		)
		return true
	}

	fun setEndpointMode(serverId: String, mode: ChannelFlowEndpointMode): Boolean {
		val current = _state.value
		val server = current.servers.firstOrNull { it.id == serverId } ?: return false
		if (server.endpointMode == mode) return true
		commit(
			current.copy(
				servers = current.servers.map { saved ->
					if (saved.id == serverId) saved.copy(endpointMode = mode) else saved
				},
			)
		)
		return true
	}

	fun remove(serverId: String) {
		val remaining = _state.value.servers.filterNot { it.id == serverId }
		val next = remaining.firstOrNull { it.id == _state.value.activeServerId } ?: remaining.firstOrNull()
		commit(
			_state.value.copy(
				servers = remaining,
				activeServerId = next?.id,
				connection = next?.connection,
			)
		)
	}

	fun removeByApiKey(apiKey: String): Boolean {
		if (apiKey.isBlank()) return false
		val ids = _state.value.servers
			.filter { it.connection.apiKey == apiKey }
			.map { it.id }
		if (ids.isEmpty()) return false
		ids.forEach { remove(it) }
		return true
	}

	fun clearConnection() {
		commit(
			_state.value.copy(
				servers = emptyList(),
				activeServerId = null,
				connection = null,
			)
		)
	}

	fun isFavorite(channelId: UUID): Boolean =
		_state.value.favoriteChannelIds.contains(channelId.toString())

	fun setFavorite(channelId: UUID, favorite: Boolean) {
		val ids = _state.value.favoriteChannelIds.toMutableSet()
		if (favorite) ids.add(channelId.toString()) else ids.remove(channelId.toString())
		commit(_state.value.copy(favoriteChannelIds = ids))
	}

	fun favoriteIds(): Set<UUID> =
		_state.value.favoriteChannelIds.mapNotNull { it.toUUIDOrNull() }.toSet()

	private fun load(): ChannelFlowServersState {
		val sources = listOf(
			"file" to readFile(file),
			"device-file" to readFile(deviceFile),
			"prefs" to prefs.getString(KEY_STATE, null),
		)
		for ((source, text) in sources) {
			val state = text?.let { ChannelFlowConnectionPersistence.decode(it) }
			if (state != null && state.connection != null) {
				Timber.i("Restored ChannelFlow server link from %s", source)
				return state
			}
		}
		return ChannelFlowServersState()
	}

	private fun commit(next: ChannelFlowServersState) {
		_state.value = next
		write()
	}

	private fun write() {
		val snapshot = _state.value
		val text = ChannelFlowConnectionPersistence.encode(snapshot)
		writeAtomic(file, text)
		if (deviceFile.absolutePath != file.absolutePath) writeAtomic(deviceFile, text)
		runCatching {
			prefs.edit { putString(KEY_STATE, text) }
		}.onFailure { error ->
			Timber.e(error, "Unable to write ChannelFlow connection preferences")
		}
	}

	private fun readFile(target: File): String? {
		if (!target.exists()) return null
		return runCatching { target.readText() }
			.onFailure { error -> Timber.e(error, "Unable to read ChannelFlow connection store %s", target.name) }
			.getOrNull()
	}

	private fun writeAtomic(target: File, text: String) {
		runCatching {
			target.parentFile?.mkdirs()
			val tmp = File(target.parentFile, "${target.name}.tmp")
			tmp.writeText(text)
			if (!tmp.renameTo(target)) {
				tmp.copyTo(target, overwrite = true)
				tmp.delete()
			}
		}.onFailure { error ->
			Timber.e(error, "Unable to write ChannelFlow connection store %s", target.name)
		}
	}

	companion object {
		const val FILE_NAME = "channelflow_connection.json"
		const val PREFS = "channelflow_servers"
		private const val KEY_STATE = "state"

		private fun deviceProtectedContext(context: Context): Context =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				context.createDeviceProtectedStorageContext()
			} else {
				context
			}
	}
}

data class ChannelFlowServersState(
	val servers: List<ChannelFlowSavedServer> = emptyList(),
	val activeServerId: String? = null,
	val connection: ChannelFlowConnection? = null,
	val favoriteChannelIds: Set<String> = emptySet(),
)
