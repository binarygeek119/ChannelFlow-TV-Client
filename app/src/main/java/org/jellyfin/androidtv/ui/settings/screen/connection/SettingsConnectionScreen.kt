package org.jellyfin.androidtv.ui.settings.screen.connection

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.channelflow.ChannelFlowActiveRoute
import org.jellyfin.androidtv.channelflow.ChannelFlowConnection
import org.jellyfin.androidtv.channelflow.ChannelFlowConnectionStore
import org.jellyfin.androidtv.channelflow.ChannelFlowEndpointMode
import org.jellyfin.androidtv.channelflow.ChannelFlowEndpointResolver
import org.jellyfin.androidtv.channelflow.ChannelFlowGuideRepository
import org.jellyfin.androidtv.channelflow.ChannelFlowRoute
import org.jellyfin.androidtv.channelflow.ChannelFlowSavedServer
import org.jellyfin.androidtv.channelflow.reloadChannelFlowMain
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun SettingsConnectionScreen() {
	val store = koinInject<ChannelFlowConnectionStore>()
	val catalog = koinInject<ChannelFlowGuideRepository>()
	val resolver = koinInject<ChannelFlowEndpointResolver>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val context = LocalContext.current
	val state by store.state.collectAsState()
	val activeRoute by resolver.active.collectAsState()
	val activeServer = state.servers.firstOrNull { it.id == state.activeServerId }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.lbl_switch_server)) },
				captionContent = { Text(stringResource(R.string.lbl_switch_server_help)) },
			)
		}

		if (state.servers.isEmpty()) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.lbl_no_saved_servers)) },
					onClick = {},
					enabled = false,
					modifier = Modifier.focusKey("no_servers"),
				)
			}
		} else {
			items(state.servers, key = { it.id }) { server ->
				val selected = server.id == state.activeServerId
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
					headingContent = { Text(server.connection.displayName()) },
					captionContent = { Text(serverUrls(server)) },
					trailingContent = { RadioButton(checked = selected) },
					onClick = {
						if (selected) return@ListButton
						if (!store.setActive(server.id)) return@ListButton
						catalog.clear()
						settingsViewModel.hide()
						context.reloadChannelFlowMain()
					},
					modifier = Modifier.focusKey("server_${server.id}"),
				)
			}
		}

		connectionRouteSection(
			server = activeServer,
			activeRoute = activeRoute,
			onModeSelected = { mode ->
				val serverId = state.activeServerId
				if (serverId != null && store.setEndpointMode(serverId, mode)) {
					// Throw away the cached reachability decision and load the guide from the
					// endpoint that is now in use.
					resolver.invalidate()
					catalog.clear()
					catalog.prefetchLatest()
				}
			},
		)
	}
}

/**
 * Lets the local/public choice be pinned per server, next to a read-out of the route the app
 * picked by itself. Hidden entirely for servers paired with an older server that only has one
 * address.
 */
private fun LazyListScope.connectionRouteSection(
	server: ChannelFlowSavedServer?,
	activeRoute: ChannelFlowActiveRoute?,
	onModeSelected: (ChannelFlowEndpointMode) -> Unit,
) {
	if (server == null || !server.connection.hasVariants) return

	item {
		ListSection(
			overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
			headingContent = { Text(stringResource(R.string.lbl_connection_route)) },
			captionContent = { Text(routeCaption(server, activeRoute)) },
		)
	}

	items(endpointModes(), key = { "endpoint_mode_${it.first.name}" }) { (mode, label) ->
		val selected = server.endpointMode == mode
		ListButton(
			headingContent = { Text(stringResource(label)) },
			enabled = endpointAvailable(mode, server.connection),
			trailingContent = { RadioButton(checked = selected) },
			onClick = { onModeSelected(mode) },
			modifier = Modifier.focusKey("endpoint_mode_${mode.name}"),
		)
	}
}

private fun endpointModes(): List<Pair<ChannelFlowEndpointMode, Int>> = listOf(
	ChannelFlowEndpointMode.AUTO to R.string.lbl_endpoint_auto,
	ChannelFlowEndpointMode.LOCAL to R.string.lbl_endpoint_local,
	ChannelFlowEndpointMode.PUBLIC to R.string.lbl_endpoint_public,
)

private fun endpointAvailable(mode: ChannelFlowEndpointMode, connection: ChannelFlowConnection?): Boolean = when (mode) {
	ChannelFlowEndpointMode.AUTO -> true
	ChannelFlowEndpointMode.LOCAL -> connection?.localEndpoint != null
	ChannelFlowEndpointMode.PUBLIC -> connection?.publicEndpoint != null
}

private fun serverUrls(server: ChannelFlowSavedServer): String =
	listOfNotNull(
		server.connection.baseUrl,
		server.connection.publicEndpoint?.baseUrl,
		server.connection.localEndpoint?.baseUrl,
	).distinctBy { it.lowercase() }
		.joinToString(" • ")

@Composable
private fun routeCaption(server: ChannelFlowSavedServer, activeRoute: ChannelFlowActiveRoute?): String = when {
	activeRoute?.serverId != server.id -> stringResource(R.string.msg_endpoint_checking)
	activeRoute.route == ChannelFlowRoute.LOCAL -> stringResource(R.string.msg_endpoint_using_local)
	else -> stringResource(R.string.msg_endpoint_using_public)
}
