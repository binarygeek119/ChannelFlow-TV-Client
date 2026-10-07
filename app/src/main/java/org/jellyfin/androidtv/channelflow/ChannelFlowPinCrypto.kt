package org.jellyfin.androidtv.channelflow

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChannelFlowPinCrypto {
	private const val KEY_SEED_PREFIX = "ChannelFlow QuickPin v1"
	const val PIN_LENGTH = 8

	private val json = Json { ignoreUnknownKeys = true }

	fun normalize(pin: String): String =
		pin.filter { it.isLetterOrDigit() }.uppercase()

	fun format(pin: String): String {
		val value = normalize(pin)
		return if (value.length == PIN_LENGTH) "${value.substring(0, 4)}-${value.substring(4)}" else value
	}

	fun decrypt(pin: String, ciphertextBase64: String): Payload {
		val normalized = normalize(pin)
		require(normalized.length == PIN_LENGTH) { "invalid pin" }

		val key = MessageDigest.getInstance("SHA-256")
			.digest((KEY_SEED_PREFIX + normalized).toByteArray(Charsets.US_ASCII))
		val packed = Base64.decode(ciphertextBase64, Base64.DEFAULT)
		require(packed.size > 28) { "invalid ciphertext" }

		val nonce = packed.copyOfRange(0, 12)
		val tag = packed.copyOfRange(packed.size - 16, packed.size)
		val ciphertext = packed.copyOfRange(12, packed.size - 16)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
		val plain = cipher.doFinal(ciphertext + tag)
		return json.decodeFromString<Payload>(plain.decodeToString())
	}

	@Serializable
	data class Payload(
		val m3u: String = "",
		val xmltv: String = "",
		// Both variants are optional: servers older than the public/local update send neither.
		val m3uPublic: String = "",
		val xmltvPublic: String = "",
		val m3uLocal: String = "",
		val xmltvLocal: String = "",
	)
}
