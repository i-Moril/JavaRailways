package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import java.util.UUID

class AttachedSignalPersistenceTest {
    private val worldId = UUID(0, 32)
    private val base = SimpleSignalsWorldDefinitionsCodec.decode(
        javaClass.getResource("/device-model-definitions.json")!!.readText(), worldId)
    private val ground = DeviceModelDefinition("demo:ground", ModelPlacement.Ground(ModelPose.IDENTITY))

    @Test
    fun `fixed current schema document without attached list decodes empty and current writer retains version`() {
        val old = javaClass.getResource("/world-definitions-without-attached-signals.json")!!.readText()
        val restored = SimpleSignalsWorldDefinitionsCodec.decode(old, worldId)
        assertTrue(restored.attachedSignals.isEmpty())
        val document = Json.parseToJsonElement(SimpleSignalsWorldDefinitionsCodec.encode(restored)).jsonObject
        assertEquals(JsonPrimitive(1), document["schemaVersion"])
        assertEquals(JsonArray(emptyList()), document["attachedSignals"])
    }

    @Test
    fun `attached models round trip their independent directed and ground placements`() {
        val attached = listOf(
            AttachedSignalDefinition("test:repeater", 4, "test:fixed-model", ground),
            AttachedSignalDefinition("test:reverse", 5, "test:fixed-model", DeviceModelDefinition("demo:head",
                ModelPlacement.RailAttached(RailPositionSnapshot(UUID(0, 171), ViewDirection.BA, 7.0),
                    ModelPose(0.0, 1.0, 2.0, 0.0, 0.6, 0.0, 0.8), true))),
            AttachedSignalDefinition("test:protection-display", 1, base.protectionSignals.first().signalId, ground))
        val document = withAttached(attached)
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(document)
        assertEquals(document, SimpleSignalsWorldDefinitionsCodec.decode(encoded, worldId))
        val saved = Json.parseToJsonElement(encoded).jsonObject.getValue("attachedSignals").jsonArray
        assertEquals(setOf("signalId", "revision", "primarySignalId", "model"), saved.first().jsonObject.keys)
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement(encoded).jsonObject["schemaVersion"])
    }

    @Test
    fun `strict attached decoder rejects missing null wrong model and invalid identity fields`() {
        val root = Json.parseToJsonElement(SimpleSignalsWorldDefinitionsCodec.encode(withAttached(listOf(
            AttachedSignalDefinition("test:attached", 1, "test:fixed-model", ground))))).jsonObject
        val attached = root.getValue("attachedSignals").jsonArray.single().jsonObject
        val invalid = listOf(
            attached - "model", attached + ("model" to JsonNull), attached + ("model" to JsonPrimitive("demo:head")),
            attached + ("signalId" to JsonPrimitive("plain")), attached + ("primarySignalId" to JsonPrimitive("plain")),
            attached + ("revision" to JsonPrimitive(-1)), attached - "primarySignalId", attached - "signalId",
            attached + ("speedLimit" to JsonPrimitive(12)),
            attached + ("model" to JsonObject(attached.getValue("model").jsonObject + ("modelId" to JsonPrimitive("bad")))),
            attached + ("model" to JsonObject(attached.getValue("model").jsonObject - "placement")))
        invalid.forEach { fields ->
            assertThrows(Exception::class.java) {
                SimpleSignalsWorldDefinitionsCodec.decode(JsonObject(root +
                    ("attachedSignals" to JsonArray(listOf(JsonObject(fields))))).toString(), worldId)
            }
        }
        assertThrows(Exception::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(JsonObject(root + ("attachedSignals" to JsonNull)).toString(), worldId)
        }
    }

    @Test
    fun `world documents and patches defensively copy attached operations`() {
        val attached = AttachedSignalDefinition("test:attached", 1, "test:fixed-model", ground)
        val additions = mutableListOf(attached)
        val removals = mutableListOf("test:removed")
        val document = withAttached(additions)
        val patch = SimpleSignalsDefinitionPatch(upsertAttachedSignals = additions, removeAttachedSignalIds = removals)
        additions.clear()
        removals.clear()
        assertEquals(listOf(attached), document.attachedSignals)
        assertEquals(listOf(attached), patch.upsertAttachedSignals)
        assertEquals(listOf("test:removed"), patch.removeAttachedSignalIds)
        assertThrows(UnsupportedOperationException::class.java) { (document.attachedSignals as MutableList).clear() }
    }

    private fun withAttached(attached: Collection<AttachedSignalDefinition>) = SimpleSignalsWorldDefinitions(
        worldId, base.definitionRevision, base.regions, base.routeTables, base.fixedBlocks, base.fixedBlockSignals,
        base.protectionSignals, base.routeIndicators, attached)
}
