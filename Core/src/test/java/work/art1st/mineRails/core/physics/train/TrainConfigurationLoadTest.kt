package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class TrainConfigurationLoadTest {
    @Test
    fun `car loading validates the configured B bogie type independently`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val factory = TrainFactory()
        registerBogieConfig(factory, "A", BogieConfigV1(emptyMap()))
        val dependency = CarBuildDependency(
            TrainBuildDependency(
                trainId = "train",
                world = world,
                trainFactory = factory,
                headLocation = RailLocationWithFacing(rail.viewAB, 10.0)
            ),
            carId = "car"
        )
        val config = CarConfigV1(
            length = 10.0,
            floorHeight = 1.0,
            bogieAType = "A",
            bogieAOffset = 3.0,
            bogieBType = "B",
            bogieBOffset = -3.0,
            connectorLengthAtA = 0.0,
            connectorLengthAtB = 0.0,
            extension = emptyMap()
        )

        assertThrows(CannotCreateTrainException::class.java) {
            config.load(dependency)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerBogieConfig(factory: TrainFactory, type: String, config: BogieConfigV1) {
        val field = TrainFactory::class.java.getDeclaredField("bogiePrototypes")
        field.isAccessible = true
        (field.get(factory) as MutableMap<String, BogieConfigV1>)[type] = config
    }
}
