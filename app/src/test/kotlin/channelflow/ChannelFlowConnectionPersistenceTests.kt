package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ChannelFlowConnectionPersistenceTests : FunSpec({
	val connection = ChannelFlowConnection(
		baseUrl = "http://10.0.0.8:8096",
		m3uUrl = "http://10.0.0.8:8096/iptv/channels.m3u",
		epgUrl = "http://10.0.0.8:8096/iptv/xmltv.xml",
		apiKey = "secret-key",
	)

	test("restores the current multi-server store") {
		val saved = ChannelFlowSavedServer(id = "server-1", connection = connection)
		val encoded = ChannelFlowConnectionPersistence.encode(
			ChannelFlowServersState(
				servers = listOf(saved),
				activeServerId = saved.id,
				connection = connection,
			)
		)

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
		decoded.activeServerId shouldBe "server-1"
		decoded.servers.single().id shouldBe "server-1"
	}

	test("restores the original single-connection store") {
		val encoded = """
			{"connection":{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u","epgUrl":"http://10.0.0.8:8096/iptv/xmltv.xml","apiKey":"secret-key"},"favoriteChannelIds":[]}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
		decoded.servers.shouldHaveSize(1)
	}

	test("restores a bare connection object") {
		val encoded = """
			{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u","epgUrl":"http://10.0.0.8:8096/iptv/xmltv.xml","apiKey":"secret-key"}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
	}

	test("recovers an API key left on the M3U URL") {
		val encoded = """
			{"connection":{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u?apiKey=secret-key","epgUrl":"http://10.0.0.8:8096/iptv/epg.xml","apiKey":""},"favoriteChannelIds":[]}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection?.apiKey shouldBe "secret-key"
	}

	test("does not treat corrupt json as an empty server list") {
		ChannelFlowConnectionPersistence.decode("{not-json") shouldBe null
		ChannelFlowConnectionPersistence.decode("") shouldBe null
		ChannelFlowConnectionPersistence.decode("   ") shouldBe null
	}

	test("restores public and local endpoints with the endpoint mode") {
		val paired = connection.copy(
			publicEndpoint = ChannelFlowEndpoint(
				baseUrl = "https://tv.example.com",
				m3uUrl = "https://tv.example.com/iptv/channels.m3u?apiKey=secret-key",
				epgUrl = "https://tv.example.com/iptv/epg.xml?apiKey=secret-key",
			),
			localEndpoint = ChannelFlowEndpoint(
				baseUrl = "http://10.0.0.8:8096",
				m3uUrl = "http://10.0.0.8:8096/iptv/channels.m3u?apiKey=secret-key",
				epgUrl = "http://10.0.0.8:8096/iptv/epg.xml?apiKey=secret-key",
			),
		)
		val saved = ChannelFlowSavedServer(
			id = "server-1",
			connection = paired,
			endpointMode = ChannelFlowEndpointMode.LOCAL,
		)
		val encoded = ChannelFlowConnectionPersistence.encode(
			ChannelFlowServersState(
				servers = listOf(saved),
				activeServerId = saved.id,
				connection = paired,
			)
		)

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe paired
		decoded.servers.single().endpointMode shouldBe ChannelFlowEndpointMode.LOCAL
		decoded.servers.single().connection.localEndpoint?.baseUrl shouldBe "http://10.0.0.8:8096"
	}

	test("pairs saved with an older app keep working without endpoint variants") {
		val encoded = """
			{"servers":[{"id":"server-1","connection":{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u","epgUrl":"http://10.0.0.8:8096/iptv/xmltv.xml","apiKey":"secret-key"}}],"activeServerId":"server-1","favoriteChannelIds":[]}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
		decoded.connection?.hasVariants shouldBe false
		decoded.servers.single().endpointMode shouldBe ChannelFlowEndpointMode.AUTO
	}
})
