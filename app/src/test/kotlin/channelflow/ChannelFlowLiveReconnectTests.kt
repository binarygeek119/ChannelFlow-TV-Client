package org.jellyfin.androidtv.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelFlowLiveReconnectTests : FunSpec({
	test("backs off reconnects and caps at five seconds") {
		ChannelFlowLiveReconnect.delayMs(0) shouldBe 250L
		ChannelFlowLiveReconnect.delayMs(1) shouldBe 500L
		ChannelFlowLiveReconnect.delayMs(5) shouldBe 5_000L
		ChannelFlowLiveReconnect.delayMs(99) shouldBe 5_000L
	}

	test("reconnects live streams until the user stops or pauses") {
		ChannelFlowLiveReconnect.shouldReconnect(
			live = true,
			userStopped = false,
			userPaused = false,
			alreadyScheduled = false,
		) shouldBe true
		ChannelFlowLiveReconnect.shouldReconnect(
			live = true,
			userStopped = true,
			userPaused = false,
			alreadyScheduled = false,
		) shouldBe false
		ChannelFlowLiveReconnect.shouldReconnect(
			live = true,
			userStopped = false,
			userPaused = true,
			alreadyScheduled = false,
		) shouldBe false
		ChannelFlowLiveReconnect.shouldReconnect(
			live = true,
			userStopped = false,
			userPaused = false,
			alreadyScheduled = true,
		) shouldBe false
		ChannelFlowLiveReconnect.shouldReconnect(
			live = false,
			userStopped = false,
			userPaused = false,
			alreadyScheduled = false,
		) shouldBe false
	}
})
