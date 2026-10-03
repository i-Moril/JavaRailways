package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import java.util.UUID

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class DeviceModelPersistenceTest {
    private val worldId = UUID(0, 32)
    private val fixture = javaClass.getResource("/device-model-definitions.json")!!.readText()

    @Test
    fun `fixed current fixture restores all devices directed placement copied values and backend defaults`() {
        val definitions = SimpleSignalsWorldDefinitionsCodec.decode(fixture, worldId)
        assertEquals(DeviceModelDefinition("demo:heads/four", ModelPlacement.RailAttached(
            RailPositionSnapshot(UUID(0, 171), ViewDirection.AB, 12.0),
            ModelPose(0.5, 2.0, -1.5, 0.0, 0.6, 0.0, 0.8), true)), definitions.fixedBlockSignals[0].model)
        assertEquals(DeviceModelDefinition("demo:ground", ModelPlacement.Ground(
            ModelPose(-10.0, 64.0, 3.0, 0.0, 0.0, 1.0, 0.0))), definitions.protectionSignals[0].model)
        assertEquals(DeviceModelDefinition("demo:indicator", ModelPlacement.RailAttached(
            RailPositionSnapshot(UUID(0, 171), ViewDirection.BA, 4.0))), definitions.routeIndicators[0].model)
        assertNull(definitions.fixedBlockSignals[1].model)
        assertNull(definitions.protectionSignals[1].model)
        assertNull(definitions.routeIndicators[1].model)
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        assertEquals(definitions, SimpleSignalsWorldDefinitionsCodec.decode(encoded, worldId))
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(1), document["schemaVersion"])
        val indicator = document.getValue("routeIndicators").jsonArray[0].jsonObject
        assertEquals(setOf("indicatorId", "revision", "entryBoundaryId", "noRouteModelVariant", "modelVariantsByRouteId", "model"), indicator.keys)
        val placement = indicator.getValue("model").jsonObject.getValue("placement").jsonObject
        assertEquals(JsonPrimitive("rail"), placement["kind"])
        assertEquals(JsonPrimitive(false), placement["followRailTilt"])
        assertEquals(7, placement.getValue("adjustment").jsonObject.size)
    }

    @Test
    fun `model references require loader package and local path syntax`() {
        listOf("head", "demo:", ":head", "Demo:head", "demo:Head", "demo:a//b", "demo:/head",
            "demo:head/", "demo:.", "demo:..", "demo:a/../b", "demo:a/./b", "demo:a:b", "demo:a\\b").forEach { id ->
            val invalid = editModel("fixedBlockSignals") { it + ("modelId" to JsonPrimitive(id)) }
            val error = assertThrows(IllegalArgumentException::class.java) { decode(invalid) }
            assertTrue(error.message!!.contains("test:fixed-model"), id)
        }
        listOf("jr_four-light.1:head", "demo:heads/four_light-1.2").forEach { id ->
            assertEquals(id, decode(editModel("fixedBlockSignals") { it + ("modelId" to JsonPrimitive(id)) }).fixedBlockSignals[0].model!!.modelId)
        }
    }

    @Test
    fun `strict placement decoder rejects unknown kinds fields missing required values and old indicator fields`() {
        val invalid = listOf(
            editModel("fixedBlockSignals") { it + ("preset" to JsonPrimitive("right")) },
            editPlacement("fixedBlockSignals") { it + ("kind" to JsonPrimitive("floating")) },
            editPlacement("fixedBlockSignals") { it + ("worldId" to JsonPrimitive(worldId.toString())) },
            editPlacement("fixedBlockSignals") { it - "railId" },
            editPlacement("fixedBlockSignals") { it - "facing" },
            editPlacement("fixedBlockSignals") { it - "arcLengthInAbMeters" },
            editPlacement("protectionSignals") { it - "pose" },
            editPlacement("protectionSignals") { it + ("followRailTilt" to JsonPrimitive(false)) },
            editPlacement("protectionSignals") { it + ("railId" to JsonPrimitive(UUID(0, 171).toString())) },
            editPose { it - "rotationW" },
            editPose { it + ("scale" to JsonPrimitive(1)) },
            fixture.replace("\"indicatorId\": \"test:indicator-model\",", "\"indicatorId\": \"test:indicator-model\", \"railId\": \"${UUID(0, 171)}\",")
        )
        invalid.forEach { contents -> assertThrows(Exception::class.java) { decode(contents) } }
    }

    @Test
    fun `canonical UUID finite coordinates and unit rotation are required without normalization`() {
        listOf("00000000-0000-0000-0000-0000000000AB", "0-0-0-0-ab", "missing").forEach { rail ->
            assertThrows(IllegalArgumentException::class.java) {
                decode(editPlacement("fixedBlockSignals") { it + ("railId" to JsonPrimitive(rail)) })
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(editPlacement("fixedBlockSignals") { it + ("arcLengthInAbMeters" to JsonPrimitive(-0.01)) })
        }
        listOf("x", "y", "z", "rotationX", "rotationY", "rotationZ", "rotationW").forEach { field ->
            listOf("NaN", "Infinity", "1e309").forEach { number ->
                assertThrows(Exception::class.java) {
                    decode(editPose { it + (field to JsonUnquotedLiteral(number)) })
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(editPose { it + ("rotationZ" to JsonPrimitive(2.0)) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(editPose { it + ("rotationZ" to JsonPrimitive(0.0)) })
        }
        val legalZ = kotlin.math.sqrt(1.0 + 0.5e-9)
        val legal = decode(editPose { it + ("rotationZ" to JsonPrimitive(legalZ)) })
        val pose = (legal.protectionSignals[0].model!!.placement as ModelPlacement.Ground).pose
        assertEquals(legalZ, pose.rotationZ())
        assertEquals(legal, decode(SimpleSignalsWorldDefinitionsCodec.encode(legal)))
        assertThrows(IllegalArgumentException::class.java) {
            decode(editPose { it + ("rotationZ" to JsonPrimitive(kotlin.math.sqrt(1.0 + 2e-9))) })
        }
        listOf("NaN", "Infinity", "1e309").forEach { number ->
            assertThrows(Exception::class.java) {
                decode(editPlacement("fixedBlockSignals") { it + ("arcLengthInAbMeters" to JsonUnquotedLiteral(number)) })
            }
        }
    }

    private fun decode(contents: String) = SimpleSignalsWorldDefinitionsCodec.decode(contents, worldId)

    private fun editModel(collection: String, transform: (JsonObject) -> Map<String, JsonElement>): String {
        val document = Json.parseToJsonElement(fixture).jsonObject
        val devices = document.getValue(collection).jsonArray.toMutableList()
        val device = devices[0].jsonObject
        devices[0] = JsonObject(device + ("model" to JsonObject(transform(device.getValue("model").jsonObject))))
        return JsonObject(document + (collection to JsonArray(devices))).toString()
    }

    private fun editPlacement(collection: String, transform: (JsonObject) -> Map<String, JsonElement>) =
        editModel(collection) { it + ("placement" to JsonObject(transform(it.getValue("placement").jsonObject))) }

    private fun editPose(transform: (JsonObject) -> Map<String, JsonElement>) = editPlacement("protectionSignals") {
        it + ("pose" to JsonObject(transform(it.getValue("pose").jsonObject)))
    }
}
