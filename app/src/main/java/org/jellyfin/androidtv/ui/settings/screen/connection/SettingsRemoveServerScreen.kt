package org.jellyfin.androidtv.ui.settings.screen.connection

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.channelflow.ChannelFlowClientSession
import org.jellyfin.androidtv.channelflow.ChannelFlowConnectionStore
import org.jellyfin.androidtv.channelflow.ChannelFlowGuideRepository
import org.jellyfin.androidtv.channelflow.reloadChannelFlowMain
import org.jellyfin.androidtv.channelflow.startChannelFlowPairingFromEmpty
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsRemoveServerScreen() {
	val store = koinInject<ChannelFlowConnectionStore>()
	val catalog = koinInject<ChannelFlowGuideRepository>()
	val session = koinInject<ChannelFlowClientSession>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val state by store.state.collectAsState()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.lbl_remove_server)) },
				captionContent = { Text(stringResource(R.string.lbl_remove_server_help)) },
			)
		}

		if (state.servers.isEmpty()) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.lbl_no_servers_to_remove)) },
					onClick = {},
					enabled = false,
					modifier = Modifier.focusKey("no_servers"),
				)
			}
		} else {
			items(state.servers, key = { it.id }) { server ->
				val selected = server.id == state.activeServerId
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
					headingContent = { Text(server.connection.displayName()) },
					captionContent = {
						Text(
							if (selected) stringResource(R.string.lbl_current_server)
							else server.connection.baseUrl
						)
					},
					onClick = {
						val removingActive = selected
						val connection = server.connection
						scope.launch {
							session.revoke(server.id, connection)
							store.remove(server.id)
							catalog.clear()
							when {
								!store.isConnected -> {
									settingsViewModel.hide()
									context.startChannelFlowPairingFromEmpty()
								}
								removingActive -> {
									settingsViewModel.hide()
									context.reloadChannelFlowMain()
								}
							}
						}
					},
					modifier = Modifier.focusKey("remove_${server.id}"),
				)
			}
		}
	}
}
