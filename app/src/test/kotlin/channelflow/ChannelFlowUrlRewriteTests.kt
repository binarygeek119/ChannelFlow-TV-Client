package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ChannelFlowUrlRewriteTests : FunSpec({
	val publicUrl = "https://tv.example.com"
	val localUrl = "http://192.168.1.20:8097"

	val connection = ChannelFlowConnection(
		baseUrl = publicUrl,
		m3uUrl = "$publicUrl/iptv/channels.m3u?apiKey=key",
		epgUrl = "$publicUrl/iptv/epg.xml?apiKey=key",
		apiKey = "key",
		publicEndpoint = ChannelFlowEndpoint(publicUrl, "$publicUrl/iptv/channels.m3u", "$publicUrl/iptv/epg.xml"),
		localEndpoint = ChannelFlowEndpoint(localUrl, "$localUrl/iptv/channels.m3u", "$localUrl/iptv/epg.xml"),
	)

	test("swaps the base of a URL while keeping path, query and fragment") {
		ChannelFlowUrls.replaceBase(
			"$publicUrl/iptv/stream/1?apiKey=key",
			publicUrl,
			localUrl,
		) shouldBe "$localUrl/iptv/stream/1?apiKey=key"

		ChannelFlowUrls.replaceBase(
			"$localUrl/iptv/stream/1#live",
			localUrl,
			publicUrl,
		) shouldBe "$publicUrl/iptv/stream/1#live"
	}

	test("keeps a base path prefix when the server runs behind one") {
		val proxied = "https://tv.example.com/channelflow"
		val direct = "http://192.168.1.20:8097"

		ChannelFlowUrls.replaceBase(
			"$proxied/iptv/stream/1?apiKey=key",
			proxied,
			direct,
		) shouldBe "$direct/iptv/stream/1?apiKey=key"

		ChannelFlowUrls.replaceBase(
			"$direct/iptv/stream/1?apiKey=key",
			direct,
			proxied,
		) shouldBe "$proxied/iptv/stream/1?apiKey=key"
	}

	test("refuses bases that only look like a prefix") {
		ChannelFlowUrls.replaceBase("http://192.168.1.20:80970/x", "$localUrl", publicUrl).shouldBeNull()
		ChannelFlowUrls.replaceBase("https://cdn.example.com/logo.png", publicUrl, localUrl).shouldBeNull()
		ChannelFlowUrls.replaceBase("$publicUrl/iptv/stream/1", "  ", localUrl).shouldBeNull()
	}

	test("moves public playlist links onto the local endpoint in use") {
		val url = "$publicUrl/iptv/stream/84c1?apiKey=key"
		connection.remapUrl(url, localUrl) shouldBe "$localUrl/iptv/stream/84c1?apiKey=key"
	}

	test("moves local playlist links onto the public endpoint in use") {
		val url = "$localUrl/iptv/stream/84c1?apiKey=key"
		connection.remapUrl(url, publicUrl) shouldBe "$publicUrl/iptv/stream/84c1?apiKey=key"
	}

	test("leaves links that are already on the active endpoint alone") {
		val url = "$localUrl/iptv/stream/84c1?apiKey=key"
		connection.remapUrl(url, localUrl) shouldBe url
	}

	test("leaves links from other hosts alone") {
		val logo = "https://cdn.example.com/logos/abc.png"
		connection.remapUrl(logo, localUrl) shouldBe logo
	}

	test("leaves links alone when the server only has one address") {
		val single = ChannelFlowConnection(
			baseUrl = publicUrl,
			m3uUrl = "$publicUrl/iptv/channels.m3u",
			epgUrl = "$publicUrl/iptv/epg.xml",
			apiKey = "key",
		)
		val url = "$publicUrl/iptv/stream/84c1"
		single.remapUrl(url, localUrl) shouldBe url
	}
})
