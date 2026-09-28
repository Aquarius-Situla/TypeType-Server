package dev.typetype.server.services

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushPayloadEncryptor(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun isValidSubscriptionKeys(p256dh: String, auth: String): Boolean = try {
        parsePublicKey(p256dh)
        decodeAuthSecret(auth)
        true
    } catch (_: InvalidWebPushSubscription) {
        false
    }

    fun encrypt(payload: String, p256dh: String, auth: String): ByteArray {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        val serverKeyPair = keyPairGenerator.generateKeyPair()
        val salt = ByteArray(SALT_LENGTH).also(secureRandom::nextBytes)
        return encryptWithMaterial(payload, p256dh, auth, serverKeyPair, salt)
    }

    internal fun encryptWithMaterial(
        payload: String,
        p256dh: String,
        auth: String,
        serverKeyPair: KeyPair,
        salt: ByteArray,
    ): ByteArray {
        require(salt.size == SALT_LENGTH)
        val plaintext = payload.toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_SIZE)
        val userAgentPublicKey = parsePublicKey(p256dh)
        val authSecret = decodeAuthSecret(auth)
        val serverPublicKey = encodePublicKey(serverKeyPair.public as ECPublicKey)
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(serverKeyPair.private)
            doPhase(userAgentPublicKey, true)
            generateSecret()
        }
        val keyInfo = concat(
            WEB_PUSH_INFO,
            byteArrayOf(0),
            decodePublicKeyBytes(p256dh),
            serverPublicKey,
        )
        val inputKeyMaterial = hkdfExpand(hkdfExtract(authSecret, sharedSecret), keyInfo, HASH_LENGTH)
        val pseudorandomKey = hkdfExtract(salt, inputKeyMaterial)
        val contentEncryptionKey = hkdfExpand(pseudorandomKey, CONTENT_ENCODING_KEY_INFO, AES_KEY_LENGTH)
        val nonce = hkdfExpand(pseudorandomKey, CONTENT_ENCODING_NONCE_INFO, NONCE_LENGTH)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentEncryptionKey, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            doFinal(plaintext + byteArrayOf(2))
        }
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
            .put(salt)
            .putInt(RECORD_SIZE)
            .put(serverPublicKey.size.toByte())
            .put(serverPublicKey)
            .array()
        return concat(header, encrypted)
    }

    private fun parsePublicKey(encoded: String): java.security.PublicKey {
        val bytes = decodePublicKeyBytes(encoded)
        val parameters = curveParameters()
        val point = ECPoint(
            BigInteger(1, bytes.copyOfRange(1, 33)),
            BigInteger(1, bytes.copyOfRange(33, 65)),
        )
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        } catch (_: GeneralSecurityException) {
            throw InvalidWebPushSubscription()
        }
    }

    private fun decodePublicKeyBytes(encoded: String): ByteArray {
        val bytes = decodeBase64Url(encoded)
        if (bytes.size != PUBLIC_KEY_LENGTH || bytes[0] != 4.toByte()) {
            throw InvalidWebPushSubscription()
        }
        return bytes
    }

    private fun decodeAuthSecret(encoded: String): ByteArray {
        val secret = decodeBase64Url(encoded)
        if (secret.size != AUTH_SECRET_LENGTH) throw InvalidWebPushSubscription()
        return secret
    }

    private fun decodeBase64Url(encoded: String): ByteArray = try {
        Base64.getUrlDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        throw InvalidWebPushSubscription()
    }

    private fun curveParameters(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }

    private fun encodePublicKey(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixedCoordinate(key.w.affineX) + fixedCoordinate(key.w.affineY)

    private fun fixedCoordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > COORDINATE_LENGTH) it.takeLast(COORDINATE_LENGTH).toByteArray() else it }
        return ByteArray(COORDINATE_LENGTH - bytes.size) + bytes
    }

    private fun hkdfExtract(salt: ByteArray, input: ByteArray): ByteArray = hmac(salt, input)

    private fun hkdfExpand(pseudorandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            previous = hmac(pseudorandomKey, concat(previous, info, byteArrayOf(counter.toByte())))
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(input)
        }

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream(parts.sumOf { it.size }).apply { parts.forEach { write(it) } }.toByteArray()

    private companion object {
        const val CURVE = "secp256r1"
        const val SALT_LENGTH = 16
        const val AUTH_SECRET_LENGTH = 16
        const val PUBLIC_KEY_LENGTH = 65
        const val COORDINATE_LENGTH = 32
        const val HEADER_LENGTH = 86
        const val RECORD_SIZE = 4096
        const val MAX_PLAINTEXT_SIZE = 3993
        const val HASH_LENGTH = 32
        const val AES_KEY_LENGTH = 16
        const val NONCE_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        val WEB_PUSH_INFO = "WebPush: info".toByteArray(StandardCharsets.US_ASCII)
        val CONTENT_ENCODING_KEY_INFO = "Content-Encoding: aes128gcm".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0)
        val CONTENT_ENCODING_NONCE_INFO = "Content-Encoding: nonce".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0)
    }
}

internal class InvalidWebPushSubscription : Exception()
