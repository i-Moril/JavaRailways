package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import java.util.UUID

class DeviceModelPersistenceTest {
    private val worldId = UUID(0, 32)
    private val fixture = javaClass.getResource("/device-model-definitions.json")!!.readText()

    @Test
    fun `logical signals persist catalog type and instance placement without a model identity`() {
        val definitions = decode(fixture)
        assertEquals("demo:fixed", definitions.fixedBlockSignals.single().signalType)
        assertEquals("demo:protection", definitions.protectionSignals.single().signalType)
        assertEquals(ModelPlacement.RailAttached(
            RailPositionSnapshot(UUID(0, 171), ViewDirection.AB, 12.0)),
            definitions.fixedBlockSignals.single().placement)
        assertInstanceOf(ModelPlacement.Ground::class.java, definitions.protectionSignals.single().placement)

        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        assertEquals(definitions, decode(encoded))
        val document = Json.parseToJsonElement(encoded).jsonObject
        listOf("fixedBlockSignals", "protectionSignals").forEach { collection ->
            val signal = document.getValue(collection).jsonArray.single().jsonObject
            assertTrue("type" in signal)
            assertTrue("placement" in signal)
            assertFalse("model" in signal)
            assertFalse("provider" in signal)
            assertFalse("packageSignalId" in signal)
        }
    }

    @Test
    fun `type is mandatory namespaced and old provider shapes are rejected`() {
        listOf("fixedBlockSignals", "protectionSignals").forEach { collection ->
            assertThrows(Exception::class.java) { decode(editPrimary(collection) { it - "type" }) }
            listOf("block", "Demo:block", "demo:", "demo:a:b").forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    decode(editPrimary(collection) { it + ("type" to JsonPrimitive(invalid)) })
                }
            }
            listOf("provider", "packageSignalId", "occupiedOutput", "clearLevels", "downstreamSignals", "model")
                .forEach { oldField ->
                    assertThrows(Exception::class.java) {
                        decode(editPrimary(collection) { it + (oldField to JsonNull) })
                    }
                }
        }
    }

    @Test
    fun `strict placement decoder rejects missing values unknown fields and invalid coordinates`() {
        val invalid = listOf(
            editPlacement("fixedBlockSignals") { it - "railId" },
            editPlacement("fixedBlockSignals") { it - "facing" },
            editPlacement("fixedBlockSignals") { it + ("worldId" to JsonPrimitive(worldId.toString())) },
            editPlacement("fixedBlockSignals") { it + ("arcLengthInAbMeters" to JsonPrimitive(-0.01)) },
            editPlacement("protectionSignals") { it - "pose" },
            editPlacement("protectionSignals") { it + ("railId" to JsonPrimitive(UUID(0, 171).toString())) }
        )
        invalid.forEach { assertThrows(Exception::class.java) { decode(it) } }
    }

    private fun decode(contents: String) = SimpleSignalsWorldDefinitionsCodec.decode(contents, worldId)

    private fun editPrimary(collection: String, transform: (JsonObject) -> Map<String, JsonElement>): String {
        val document = Json.parseToJsonElement(fixture).jsonObject
        val devices = document.getValue(collection).jsonArray.toMutableList()
        devices[0] = JsonObject(transform(devices[0].jsonObject))
        return JsonObject(document + (collection to JsonArray(devices))).toString()
    }

    private fun editPlacement(collection: String, transform: (JsonObject) -> Map<String, JsonElement>) =
        editPrimary(collection) { device ->
            device + ("placement" to JsonObject(transform(device.getValue("placement").jsonObject)))
        }
}
