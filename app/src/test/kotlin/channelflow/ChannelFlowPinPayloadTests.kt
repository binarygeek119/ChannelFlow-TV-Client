package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ChannelFlowPinPayloadTests : FunSpec({
	val json = Json { ignoreUnknownKeys = true }

	test("reads the six URL keys sent by current servers") {
		val payload = json.decodeFromString<ChannelFlowPinCrypto.Payload>(
			"""
			{
			  "m3u": "http://192.168.1.20:8097/iptv/channels.m3u?apiKey=key",
			  "xmltv": "http://192.168.1.20:8097/iptv/epg.xml?apiKey=key",
			  "m3uPublic": "https://tv.example.com/iptv/channels.m3u?apiKey=key",
			  "xmltvPublic": "https://tv.example.com/iptv/epg.xml?apiKey=key",
			  "m3uLocal": "http://192.168.1.20:8097/iptv/channels.m3u?apiKey=key",
			  "xmltvLocal": "http://192.168.1.20:8097/iptv/epg.xml?apiKey=key"
			}
			""".trimIndent(),
		)

		payload.m3u shouldBe "http://192.168.1.20:8097/iptv/channels.m3u?apiKey=key"
		payload.m3uPublic shouldBe "https://tv.example.com/iptv/channels.m3u?apiKey=key"
		payload.xmltvPublic shouldBe "https://tv.example.com/iptv/epg.xml?apiKey=key"
		payload.m3uLocal shouldBe "http://192.168.1.20:8097/iptv/channels.m3u?apiKey=key"
		payload.xmltvLocal shouldBe "http://192.168.1.20:8097/iptv/epg.xml?apiKey=key"
	}

	test("treats a payload from an older server as having no variants") {
		val payload = json.decodeFromString<ChannelFlowPinCrypto.Payload>(
			"""{"m3u":"http://10.0.0.8:8096/iptv/channels.m3u","xmltv":"http://10.0.0.8:8096/iptv/epg.xml"}""",
		)

		payload.m3u shouldBe "http://10.0.0.8:8096/iptv/channels.m3u"
		payload.m3uPublic shouldBe ""
		payload.xmltvPublic shouldBe ""
		payload.m3uLocal shouldBe ""
		payload.xmltvLocal shouldBe ""
	}

	test("ignores keys it does not know yet") {
		val payload = json.decodeFromString<ChannelFlowPinCrypto.Payload>(
			"""{"m3u":"m","xmltv":"e","m3uCarPlay":"nope"}""",
		)

		payload.m3u shouldBe "m"
		payload.xmltv shouldBe "e"
	}
})
