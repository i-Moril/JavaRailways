package work.art1st.javarailways.core.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataTypeRegistry
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.*
import java.util.concurrent.atomic.AtomicInteger

class ExtensionDataCodecTest {
    @Serializable
    data class Payload(val value: String) : SerializableExtensionData()

    @Serializable
    data class OtherPayload(val number: Int) : SerializableExtensionData()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun `fixed envelope is strict and rejects versions before decoding payload`() {
        val registry = ExtensionDataTypeRegistry()
        val calls = AtomicInteger()
        registry.register("test:payload", 1, Payload::class, countingSerializer(calls))
        val fixed = """{"typeId":"test:payload","schemaVersion":1,"payload":{"value":"saved"}}"""
        assertEquals(Payload("saved"), json.decodeFromString(registry.serializer, fixed))
        assertEquals(1, calls.get())
        assertEquals(fixed, json.encodeToString(registry.serializer, Payload("saved")))
        calls.set(0)
        listOf(
            fixed.replace("test:payload", "test:unknown"),
            fixed.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            fixed.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            fixed.replace("\"schemaVersion\":1,", ""),
            fixed.replace("\"typeId\":\"test:payload\"", "\"typeId\":1"),
            fixed.replace("\"payload\":", "\"unexpected\":0,\"payload\":"),
            """{"type":"work.art1st.LegacyPayload","value":"saved"}""",
            "[]"
        ).forEach { malformed ->
            assertThrows(SerializationException::class.java, { json.decodeFromString(registry.serializer, malformed) }, malformed)
        }
        assertEquals(0, calls.get(), "invalid envelopes must not invoke the registered decoder")
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(registry.serializer, fixed.replace("\"value\":\"saved\"", "\"value\":\"saved\",\"unknown\":true"))
        }
        assertThrows(SerializationException::class.java) {
            json.encodeToString(registry.serializer, OtherPayload(1))
        }
    }

    @Test
    fun `registration is explicit idempotent and rejects remapping`() {
        val registry = ExtensionDataTypeRegistry()
        registry.register("test:payload", 1, Payload::class, Payload.serializer())
        registry.register("test:payload", 1, Payload::class, Payload.serializer())
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("test:payload", 2, Payload::class, Payload.serializer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("test:other", 1, Payload::class, Payload.serializer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("test:payload", 1, OtherPayload::class, OtherPayload.serializer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("not-namespaced", 1, OtherPayload::class, OtherPayload.serializer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("test:other", 0, OtherPayload::class, OtherPayload.serializer())
        }
        assertThrows(SerializationException::class.java) {
            json.encodeToString(ExtensionDataTypeRegistry().serializer, Payload("not globally discovered"))
        }
    }

    @Test
    fun `train car and bogie maps use the registered envelope and root version precedes all payloads`() {
        val calls = AtomicInteger()
        val registry = ExtensionDataTypeRegistry.global
        registry.register("core_test:codec_payload", 1, Payload::class, countingSerializer(calls))
        val location = RailLocationWithFacing.SavedRailLocationWithFacingV1(
            "00000000-0000-0000-0000-000000000001", 10.0, ViewDirection.AB
        )
        val bogie = SavedBogieV1(location, true, mapOf("TEST" to Payload("bogie")))
        val config = CarConfigV1(10.0, 0.5, null, 3.0, null, -3.0, 0.3, 0.3,
            mapOf("TEST" to Payload("car")))
        val train = SavedTrainV1("train", "Train", listOf(SavedCarV1("car", bogie, bogie, config)),
            false, mapOf("TEST" to Payload("train")), location, location)
        val snapshot = CoreWorld.SavedRuntimeV1(1, 7L, 9L, listOf(train))
        val encoded = WorldSnapshotCodec.encodeRuntime(snapshot)
        assertFalse(encoded.contains("ExtensionDataCodecTest"))
        assertEquals(4, Regex("\"typeId\":\"core_test:codec_payload\"").findAll(encoded).count())
        assertEquals(snapshot, WorldSnapshotCodec.decodeRuntime(encoded))
        assertEquals(4, calls.get(), "both bogies, car config and train must use the same codec boundary")
        calls.set(0)
        val future = encoded.replace("\"formatVersion\":1", "\"formatVersion\":2")
        val failure = assertThrows(IllegalArgumentException::class.java) { WorldSnapshotCodec.decodeRuntime(future) }
        assertTrue(failure.message!!.contains("Unsupported core runtime snapshot version"))
        assertEquals(0, calls.get())
    }

    private fun countingSerializer(calls: AtomicInteger): KSerializer<Payload> = object : KSerializer<Payload> {
        private val delegate = Payload.serializer()
        override val descriptor = delegate.descriptor
        override fun serialize(encoder: Encoder, value: Payload) = delegate.serialize(encoder, value)
        override fun deserialize(decoder: Decoder): Payload {
            calls.incrementAndGet()
            return delegate.deserialize(decoder)
        }
    }
}
