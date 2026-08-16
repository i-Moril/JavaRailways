package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TrainPackageSnapshotTest {
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
