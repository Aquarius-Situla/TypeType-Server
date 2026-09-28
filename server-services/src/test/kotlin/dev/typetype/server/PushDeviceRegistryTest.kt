package dev.typetype.server

import dev.typetype.server.models.PushDeviceRegistrationRequest
import dev.typetype.server.services.DeviceRegistrationResult
import dev.typetype.server.services.EndpointValidationResult
import dev.typetype.server.services.PushDeviceRegistry
import dev.typetype.server.services.UnifiedPushEndpointValidator
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress

class PushDeviceRegistryTest {
    private val validator = UnifiedPushEndpointValidator {
        arrayOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
    }
    private val registry = PushDeviceRegistry(validator)

    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()
    }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `registration replaces one device and prevents endpoint sharing`() = runTest {
        val first = registry.register("user-a", request("device-a", "https://push.example/a"))
        assertTrue(first is DeviceRegistrationResult.Success)
        val replacement = registry.register("user-a", request("device-a", "https://push.example/b"))
        assertTrue(replacement is DeviceRegistrationResult.Success)
        assertEquals(1, registry.list("user-a").size)
        assertEquals(
            DeviceRegistrationResult.EndpointConflict,
            registry.register("user-b", request("device-b", "https://push.example/b")),
        )
        val active = registry.activeDevices("user-a")
        assertEquals("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8", active.single().p256dh)
        assertEquals("BTBZMqHH6r4Tts7J_aSIgg", active.single().auth)
    }

    @Test
    fun registrationRejectsMalformedEncryptionKeys() = runTest {
        val request = request("device-a", "https://push.example/a").copy(p256dh = "not-a-key")
        assertEquals(DeviceRegistrationResult.Invalid("encryption_keys"), registry.register("user-a", request))
    }

    private fun request(deviceId: String, endpoint: String) = PushDeviceRegistrationRequest(
        deviceId = deviceId,
        endpoint = endpoint,
        p256dh = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
        auth = "BTBZMqHH6r4Tts7J_aSIgg",
    )
}
