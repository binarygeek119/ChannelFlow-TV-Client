package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class M3uParserTests : FunSpec({
	test("parses ChannelFlow playlist entries") {
		val playlist = """
			#EXTM3U
			#EXTINF:-1 tvg-id="11111111222233334444555555555555" tvg-chno="5.1" tvg-name="News",News
			http://server/iptv/stream/11111111222233334444555555555555?apiKey=secret
		""".trimIndent()

		val channels = M3uParser.parse(playlist)
		channels.shouldHaveSize(1)
		channels[0].name shouldBe "News"
		channels[0].number shouldBe "5.1"
		channels[0].streamUrl.shouldStartWith("http://server/iptv/stream/")
	}

	test("skips VLC option lines and still finds the stream URL") {
		val playlist = """
			#EXTM3U
			#EXTINF:-1 tvg-name="Movie",Movie
			#EXTVLCOPT:http-user-agent=VLC
			https://cdn.example/live/index.m3u8
		""".trimIndent()

		val channels = M3uParser.parse(playlist)
		channels.shouldHaveSize(1)
		channels[0].streamUrl shouldBe "https://cdn.example/live/index.m3u8"
	}

	test("writes a VLC IPTV playlist for a channel stream") {
		val channelId = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
		val playlist = ChannelFlowVlcPlaylist.text(
			streamUrl = "https://server/iptv/stream/11111111222233334444555555555555?apiKey=secret",
			name = "News",
			channelId = channelId,
			number = "5.1",
			apiKey = "secret",
		)
		playlist.shouldStartWith("#EXTM3U")
		playlist.contains("tvg-id=\"11111111222233334444555555555555\"") shouldBe true
		playlist.contains("#EXTVLCOPT:http-user-agent=ChannelFlow-TV") shouldBe true
		playlist.contains("#EXTVLCOPT:network-caching=${ChannelFlowVlcPlaylist.START_CACHING_MS}") shouldBe true
		playlist.contains("#EXTVLCOPT:prefetch-buffer-size=${ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB}") shouldBe true
		playlist.contains("#EXTVLCOPT:ts-cc-check=0") shouldBe true
		playlist.contains("http-reconnect") shouldBe false
		playlist.contains("#EXTVLCOPT:http-header=X-Api-Key: secret") shouldBe true
		ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB shouldBe 600 * 1024
		playlist.contains("https://server/iptv/stream/11111111222233334444555555555555?apiKey=secret") shouldBe true
		M3uParser.parse(playlist).shouldHaveSize(1)
		M3uParser.parse(playlist)[0].name shouldBe "News"
	}

	test("adds an apiKey query to a bare stream URL") {
		ChannelFlowVlcPlaylist.withApiKey("http://server/iptv/stream/abc", "secret") shouldBe
			"http://server/iptv/stream/abc?apiKey=secret"
		ChannelFlowVlcPlaylist.withApiKey("http://server/iptv/stream/abc?x=1", "secret") shouldBe
			"http://server/iptv/stream/abc?x=1&apiKey=secret"
		ChannelFlowVlcPlaylist.withApiKey("http://server/iptv/stream/abc?apiKey=old", "secret") shouldBe
			"http://server/iptv/stream/abc?apiKey=old"
	}

	test("adds a live-edge cache buster without dropping the apiKey") {
		ChannelFlowVlcPlaylist.withLiveEdge("http://server/iptv/stream/abc?apiKey=secret", 42) shouldBe
			"http://server/iptv/stream/abc?apiKey=secret&_r=42"
		ChannelFlowVlcPlaylist.withLiveEdge("http://server/iptv/stream/abc?apiKey=secret&_r=1", 99) shouldBe
			"http://server/iptv/stream/abc?apiKey=secret&_r=99"
	}

	test("detects HLS vs MPEG-TS mime types") {
		ChannelFlowStream.mimeType("https://x/live/index.m3u8") shouldBe ChannelFlowStream.MIME_HLS
		ChannelFlowStream.mimeType("http://server/iptv/stream/abc") shouldBe ChannelFlowStream.MIME_TS
		ChannelFlowStream.container("https://x/live/index.m3u8") shouldBe "hls"
	}
})
