package dev.typetype.server

import dev.typetype.server.services.WebPushPayloadEncryptor
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebPushPayloadEncryptorTest {
    @Test
    fun encryptionMatchesRfc8291Example() {
        val userAgentKey = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        val auth = "BTBZMqHH6r4Tts7J_aSIgg"
        val serverKey = keyPair(
            "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
        )
        val encrypted = WebPushPayloadEncryptor().encryptWithMaterial(
            "When I grow up, I want to be a watermelon",
            userAgentKey,
            auth,
            serverKey,
            Base64.getUrlDecoder().decode("DGv6ra1nlYgDCS1FRnbzlw"),
        )

        assertEquals(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted),
        )
    }

    private fun keyPair(privateKey: String, publicKey: String): KeyPair {
        val parameters = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val factory = KeyFactory.getInstance("EC")
        val privateBytes = Base64.getUrlDecoder().decode(privateKey)
        val privateSpec = ECPrivateKeySpec(BigInteger(1, privateBytes), parameters)
        val publicBytes = Base64.getUrlDecoder().decode(publicKey)
        val publicSpec = ECPublicKeySpec(
            ECPoint(
                BigInteger(1, publicBytes.copyOfRange(1, 33)),
                BigInteger(1, publicBytes.copyOfRange(33, 65)),
            ),
            parameters,
        )
        return KeyPair(factory.generatePublic(publicSpec), factory.generatePrivate(privateSpec))
    }
}
