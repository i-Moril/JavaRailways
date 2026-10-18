package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.encodeToString
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
