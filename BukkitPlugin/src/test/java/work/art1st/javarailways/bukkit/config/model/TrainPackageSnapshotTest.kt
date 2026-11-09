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
        assertEquals(TrainDisplayResources(), decoded.displays)
        val rebuilt = SavedTrainPackageSnapshot.build(decoded.packageName, decoded.actions, decoded.behavior,
            decoded.control, decoded.carControls, decoded.displays)
        assertEquals(decoded.hash, rebuilt.hash)
        val encoding = Json { encodeDefaults = true; explicitNulls = false }.encodeToString(rebuilt)
        assertTrue("\"displays\"" !in encoding)
        val file = TrainPackageSnapshotStore.snapshotFile(directory, decoded.hash)
        file.parentFile.mkdirs(); file.writeText(text)
        assertEquals(decoded, TrainPackageSnapshotStore.load(directory, decoded.hash) { error(it) })
    }

    @Test
    fun `display snapshots are canonical immutable and cover source declaration and default changes`() {
        val templates = linkedMapOf("z" to TrainDisplayTemplate("displays/z.xml", "<screen/>\n"),
            "a" to TrainDisplayTemplate("displays/a.xml", "<screen><script><![CDATA[document.createElement('span')]]></script></screen>"))
        val defaults = linkedMapOf("z-category" to "z", "a-category" to "a")
        fun capture(resources: TrainDisplayResources) = snapshot().let {
            SavedTrainPackageSnapshot.build(it.packageName, it.actions, it.behavior, it.control, it.carControls, resources)
        }
        val original = capture(TrainDisplayResources(templates, defaults))
        val reversed = capture(TrainDisplayResources(templates.entries.reversed().associate { it.toPair() }, defaults.entries.reversed().associate { it.toPair() }))
        assertEquals(original.hash, reversed.hash)
        assertEquals(listOf("a", "z"), original.displays.templates.keys.toList())
        assertNotEquals(snapshot().hash, original.hash)
        val changedSource = templates + ("z" to templates.getValue("z").copy(source = "changed"))
        val changedFile = templates + ("z" to templates.getValue("z").copy(file = "other.xml"))
        assertNotEquals(original.hash, capture(TrainDisplayResources(changedSource, defaults)).hash)
        assertNotEquals(original.hash, capture(TrainDisplayResources(changedFile, defaults)).hash)
        assertNotEquals(original.hash, capture(TrainDisplayResources(templates, defaults + ("a-category" to "z"))).hash)
        templates.clear(); defaults.clear()
        assertEquals(2, original.displays.templates.size)
        val restored = Json.decodeFromString<SavedTrainPackageSnapshot>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertThrows<UnsupportedOperationException> { (restored.displays.templates as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (restored.displays.defaults as MutableMap).clear() }
    }

    @Test
    fun `display constructors and strict JSON decode enforce identifiers paths and references`() {
        assertThrows<IllegalArgumentException> { TrainDisplayTemplate("../bad.xml", "source") }
        assertThrows<IllegalArgumentException> { TrainDisplayResources(mapOf("bad id" to TrainDisplayTemplate("a.xml", ""))) }
        assertThrows<IllegalArgumentException> { TrainDisplayResources(defaults = mapOf("cabin" to "missing")) }
        for (text in listOf(
            "{\"templates\":{},\"defaults\":{\"cabin\":\"missing\"}}",
            "{\"templates\":{\"bad id\":{\"file\":\"a.xml\",\"source\":\"\"}}}",
            "{\"templates\":{\"a\":{\"file\":\"../bad.xml\",\"source\":\"\"}}}",
            "{\"templates\":{},\"extra\":true}"
        )) assertThrows<Exception>(text) { Json.decodeFromString<TrainDisplayResources>(text) }
    }

    @Test
    fun `store rehash rejects display source declaration defaults and reference corruption`() {
        val base = snapshot()
        val displays = TrainDisplayResources(mapOf("a" to TrainDisplayTemplate("a.xml", "raw a"), "b" to TrainDisplayTemplate("b.xml", "raw b")), mapOf("cabin" to "a"))
        val original = SavedTrainPackageSnapshot.build(base.packageName, base.actions, base.behavior, base.control, base.carControls, displays)
        val file = TrainPackageSnapshotStore.snapshotFile(directory, original.hash)
        file.parentFile.mkdirs(); file.writeText(Json.encodeToString(original))
        assertEquals(original, TrainPackageSnapshotStore.load(directory, original.hash) { error(it) })
        for (changed in listOf(
            TrainDisplayResources(displays.templates + ("a" to displays.templates.getValue("a").copy(source = "tampered")), displays.defaults),
            TrainDisplayResources(displays.templates + ("a" to displays.templates.getValue("a").copy(file = "other.xml")), displays.defaults),
            TrainDisplayResources(displays.templates, mapOf("cabin" to "b")),
            TrainDisplayResources()
        )) {
            file.writeText(Json.encodeToString(original.copy(displays = changed)))
            val failures = mutableListOf<String>()
            assertNull(TrainPackageSnapshotStore.load(directory, original.hash, failures::add))
            assertTrue(failures.single().contains("hash does not match"))
        }
        val text = Json.encodeToString(original).replace("\"cabin\":\"a\"", "\"cabin\":\"missing\"")
        file.writeText(text)
        val failures = mutableListOf<String>()
        assertNull(TrainPackageSnapshotStore.load(directory, original.hash, failures::add))
        assertTrue(failures.single().contains("unknown template"))
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
