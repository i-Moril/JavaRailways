package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.encodeToString
import work.art1st.javarailways.basic.control.TrainDynamics
import kotlinx.serialization.json.Json
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import java.io.File
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources

class TrainPackageSnapshotTest {
    @TempDir lateinit var directory: File

    @Test
    fun `snapshot store rejects altered contents even when embedded hash matches filename`() {
        val original = snapshot()
        val file = TrainPackageSnapshotStore.snapshotFile(directory, original.hash)
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(original))
        assertEquals(original, TrainPackageSnapshotStore.load(directory, original.hash) { error(it) })
        file.writeText(Json.encodeToString(original.copy(control = TrainControlConfig(maxSpeed = 99.0))))
        val failures = mutableListOf<String>()
        assertNull(TrainPackageSnapshotStore.load(directory, original.hash, failures::add))
        assertTrue(failures.single().contains("hash does not match"))
    }

    @Test
    fun `snapshot hash covers behavior actions control and car controls`() {
        val base = snapshot()

        assertNotEquals(base.hash, snapshot(behavior = "function changed() {}").hash)
        assertNotEquals(base.hash, snapshot(actionSound = "other:sound").hash)
        assertNotEquals(base.hash, snapshot(maxSpeed = 42.0).hash)
        assertNotEquals(base.hash, snapshot(carMass = 12345.0).hash)
        assertEquals(base, snapshot())
    }

    @Test
    fun `snapshot identity and serialization include per-car electric brake and equipment`() {
        val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.25, nominalCylinderPressureKpa = 420.0,
            tractionApplyRateNewtonsPerSecond = 123000.0)
        val controlled = snapshot().copy(carControls = mapOf("head" to CarControlConfig(
            equipment = equipment, brake = CarBrakeControlConfig(regenerativeForce = 30000.0))))
        val captured = SavedTrainPackageSnapshot.build(controlled.packageName, controlled.actions,
            controlled.behavior, controlled.control, controlled.carControls)
        assertNotEquals(snapshot().hash, captured.hash)
        val text = Json.encodeToString(captured)
        val restored = Json.decodeFromString<SavedTrainPackageSnapshot>(text)
        assertEquals(equipment, restored.carControls.getValue("head").equipment)
        assertEquals(30000.0, restored.carControls.getValue("head").electricBrakeMaxForce)
        assertEquals(captured, restored)
        val file = TrainPackageSnapshotStore.snapshotFile(directory, captured.hash)
        file.parentFile.mkdirs(); file.writeText(text)
        assertEquals(captured, TrainPackageSnapshotStore.load(directory, captured.hash) { error(it) })
        val changed = captured.carControls.getValue("head").copy(equipment = equipment.copy(commandDelaySeconds = 0.3))
        assertNotEquals(captured.hash, SavedTrainPackageSnapshot.build(captured.packageName, captured.actions,
            captured.behavior, captured.control, mapOf("head" to changed)).hash)
    }

    @Test
    fun `fixed pre-display snapshot retains canonical hash and omits empty addition`() {
        val text = requireNotNull(javaClass.getResource("/model/package-without-displays.json")).readText()
        val decoded = Json.decodeFromString<SavedTrainPackageSnapshot>(text)
        assertEquals("5d84a4c86fd800a161165c46a04388f259fdac5a3d2f63bd1274c46ea659bd20", decoded.hash)
        assertEquals(emptyMap<String, PartExtensionResources>(), decoded.extensions)
        val rebuilt = SavedTrainPackageSnapshot.build(decoded.packageName, decoded.actions, decoded.behavior,
            decoded.control, decoded.carControls, decoded.extensions)
        assertEquals(decoded.hash, rebuilt.hash)
        val encoding = Json { encodeDefaults = true; explicitNulls = false }.encodeToString(rebuilt)
        assertTrue("\"extensions\"" !in encoding)
        val file = TrainPackageSnapshotStore.snapshotFile(directory, decoded.hash)
        file.parentFile.mkdirs(); file.writeText(text)
        assertEquals(decoded, TrainPackageSnapshotStore.load(directory, decoded.hash) { error(it) })
    }

    @Test
    fun `extension snapshots retain unknown addon raw text canonical ordering immutability and store rehash`() {
        val files = linkedMapOf("z.xml" to "<screen/>\n", "a.txt" to "车站 <script>raw</script>")
        fun capture(value: Map<String, PartExtensionResources>) = snapshot().let {
            SavedTrainPackageSnapshot.build(it.packageName, it.actions, it.behavior, it.control, it.carControls, value)
        }
        val original = capture(mapOf("unknown:part" to PartExtensionResources(1, files)))
        val reversed = capture(mapOf("unknown:part" to PartExtensionResources(1, files.entries.reversed().associate { it.toPair() })))
        assertEquals(original.hash, reversed.hash)
        assertNotEquals(snapshot().hash, original.hash)
        assertEquals(listOf("a.txt", "z.xml"), original.extensions.getValue("unknown:part").files.keys.toList())
        val mutableExtensions = original.extensions.toMutableMap()
        val copied = original.copy(extensions = mutableExtensions)
        mutableExtensions.clear()
        assertEquals(original.extensions, copied.extensions)
        assertThrows<UnsupportedOperationException> { (copied.extensions as MutableMap).clear() }
        val restored = Json.decodeFromString<SavedTrainPackageSnapshot>(Json.encodeToString(original))
        assertEquals(original, restored)
        files.clear()
        assertEquals(2, restored.extensions.getValue("unknown:part").files.size)
        assertThrows<UnsupportedOperationException> { (restored.extensions as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (restored.extensions.getValue("unknown:part").files as MutableMap).clear() }
        val file = TrainPackageSnapshotStore.snapshotFile(directory, original.hash)
        file.parentFile.mkdirs(); file.writeText(Json.encodeToString(original))
        assertEquals(original, TrainPackageSnapshotStore.load(directory, original.hash) { error(it) })
        for (changed in listOf(mapOf("unknown:part" to PartExtensionResources(1, mapOf("a.txt" to "tampered"))),
            mapOf("unknown:part" to PartExtensionResources(2, original.extensions.getValue("unknown:part").files)),
            emptyMap())) {
            assertNotEquals(original.hash, capture(changed).hash)
            file.writeText(Json.encodeToString(original.copy(extensions = changed)))
            val failures = mutableListOf<String>()
            assertNull(TrainPackageSnapshotStore.load(directory, original.hash, failures::add))
            assertTrue(failures.single().contains("hash does not match"))
        }
        assertThrows<IllegalArgumentException> { PartExtensionResources(1, mapOf("../escape.txt" to "raw")) }
    }

    private fun snapshot(
        behavior: String = "function door() {}",
        actionSound: String = "minecraft:block.note_block.bell",
        maxSpeed: Double = 20.0,
        carMass: Double = 1000.0
    ): SavedTrainPackageSnapshot = SavedTrainPackageSnapshot.build(
        packageName = "test",
        actions = mapOf(
            "bell" to TrainActionConfig(
                name = "bell",
                sounds = listOf(TrainActionSoundConfig(actionSound))
            )
        ),
        behavior = TrainBehaviorScript("behavior.js", behavior),
        control = TrainControlConfig(maxSpeed = maxSpeed),
        carControls = mapOf("head" to CarControlConfig(mass = carMass))
    )
}
