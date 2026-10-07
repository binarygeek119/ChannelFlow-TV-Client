package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException

private class FakeRouteCache : ChannelFlowRouteCache {
	val entries = mutableMapOf<String, ChannelFlowRouteCacheEntry>()

	override fun read(key: String): ChannelFlowRouteCacheEntry? = entries[key]
	override fun write(key: String, entry: ChannelFlowRouteCacheEntry) {
		entries[key] = entry
	}
	override fun remove(key: String) {
		entries.remove(key)
	}
	override fun clear() = entries.clear()
}

class ChannelFlowEndpointResolverTests : FunSpec({
	val publicUrl = "https://tv.example.com"
	val localUrl = "http://192.168.1.20:8097"
	val serverId = "server-1"

	fun connection(withVariants: Boolean = true) = ChannelFlowConnection(
		baseUrl = publicUrl,
		m3uUrl = "$publicUrl/iptv/channels.m3u?apiKey=key",
		epgUrl = "$publicUrl/iptv/epg.xml?apiKey=key",
		apiKey = "key",
		publicEndpoint = if (withVariants) ChannelFlowEndpoint(
			baseUrl = publicUrl,
			m3uUrl = "$publicUrl/iptv/channels.m3u?apiKey=key",
			epgUrl = "$publicUrl/iptv/epg.xml?apiKey=key",
		) else null,
		localEndpoint = if (withVariants) ChannelFlowEndpoint(
			baseUrl = localUrl,
			m3uUrl = "$localUrl/iptv/channels.m3u?apiKey=key",
			epgUrl = "$localUrl/iptv/epg.xml?apiKey=key",
		) else null,
	)

	test("uses the local endpoint when the local URL answers") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true })

		val active = resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)

		active.baseUrl shouldBe localUrl
		probes shouldBe 1
		resolver.peekActive(serverId)?.route shouldBe ChannelFlowRoute.LOCAL
	}

	test("uses the public endpoint when the local URL does not answer") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; false })

		val active = resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)

		active.baseUrl shouldBe publicUrl
		probes shouldBe 1
		resolver.peekActive(serverId)?.route shouldBe ChannelFlowRoute.PUBLIC
	}

	test("honours a pinned endpoint mode without probing") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true })

		resolver.resolve(connection(), ChannelFlowEndpointMode.LOCAL, serverId).baseUrl shouldBe localUrl
		resolver.resolve(connection(), ChannelFlowEndpointMode.PUBLIC, serverId).baseUrl shouldBe publicUrl
		probes shouldBe 0
	}

	test("keeps servers paired with an older server on the primary endpoint") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true })

		val active = resolver.resolve(connection(withVariants = false), ChannelFlowEndpointMode.AUTO, serverId)

		active.baseUrl shouldBe publicUrl
		probes shouldBe 0
		resolver.peekActive(serverId).shouldBeNull()
	}

	test("reuses the cached decision until it expires") {
		var probes = 0
		var now = 0L
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true }, { now })

		repeat(3) { resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId) }
		probes shouldBe 1

		now = 29_000L
		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		probes shouldBe 1

		now = 31_000L
		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		probes shouldBe 2
	}

	test("re-probes soon after the local URL was unreachable") {
		var probes = 0
		var now = 0L
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; false }, { now })

		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		probes shouldBe 1

		now = 11_000L
		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		probes shouldBe 2
	}

	test("fails over from the local endpoint to the public one and remembers it") {
		var probes = 0
		var now = 0L
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true }, { now })
		val seen = mutableListOf<String>()

		val result = resolver.withFailover(connection(), ChannelFlowEndpointMode.AUTO, serverId) { active ->
			seen += active.baseUrl
			if (active.baseUrl == localUrl) throw IOException("local network gone")
			"ok"
		}

		result shouldBe "ok"
		seen shouldBe listOf(localUrl, publicUrl)
		resolver.peekActive(serverId)?.route shouldBe ChannelFlowRoute.PUBLIC

		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId).baseUrl shouldBe publicUrl
		probes shouldBe 1
	}

	test("does not fail over when the local URL cannot be reached") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; false })
		var attempts = 0

		runCatching {
			resolver.withFailover(connection(), ChannelFlowEndpointMode.AUTO, serverId) {
				attempts++
				throw IOException("public network gone")
			}
		}.isFailure shouldBe true

		attempts shouldBe 1
		probes shouldBe 2
	}

	test("drops the cached decision when both endpoints fail") {
		val cache = FakeRouteCache()
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(cache, { probes++; true })

		runCatching {
			resolver.withFailover(connection(), ChannelFlowEndpointMode.AUTO, serverId) {
				throw IOException("server unreachable")
			}
		}.isFailure shouldBe true

		probes shouldBe 1
		cache.entries.size shouldBe 0
		resolver.peekActive(serverId).shouldBeNull()
	}

	test("invalidate throws away the cached decision") {
		var probes = 0
		val resolver = ChannelFlowEndpointResolver(FakeRouteCache(), { probes++; true })

		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		resolver.invalidate()

		resolver.peekActive(serverId).shouldBeNull()
		resolver.resolve(connection(), ChannelFlowEndpointMode.AUTO, serverId)
		probes shouldBe 2
	}
})
