package work.art1st.javarailways.bukkit.command

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.TickManagerImpl

class TrainDeletionSelectionTest {
    private val world = createTestWorld()

    @AfterEach
    fun closeWorld() {
        try {
            world.physicsCoordinator.close()
        } finally {
            (world.tickManager as TickManagerImpl).close()
        }
    }

    @Test
    fun `unknown explicit id does not fall back to a nearby train`() {
        val nearby = createTrain()
        var nearbyLookups = 0
        val selected = selectTrainForDeletion(world, "typo") {
            nearbyLookups++
            nearby
        }
        assertNull(selected)
        assertEquals(0, nearbyLookups)
        assertSame(nearby, world.getTrain(nearby.id))
    }

    @Test
    fun `explicit id selects exact train without querying proximity`() {
        val train = createTrain()
        assertSame(train, selectTrainForDeletion(world, train.id) { error("Must not query nearby trains") })
    }

    @Test
    fun `omitted id still uses nearest train`() {
        val train = createTrain()
        var nearbyLookups = 0
        assertSame(train, selectTrainForDeletion(world, null) {
            nearbyLookups++
            train
        })
        assertEquals(1, nearbyLookups)
    }

    @Test
    fun `omitted id without nearby train has no target`() {
        assertNull(selectTrainForDeletion(world, null) { null })
    }

    private fun createTrain() = createTrainWithConfig(
        "nearby",
        createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(80.0, 1)),
        world,
        listOf(10.0)
    )
}
