package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.CarDefinition
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*

@OptIn(ExtensionData::class)
class TrainResourceConfigurationTest {
    @Test
    fun `prepared update replaces active dynamics and speed offset only when installed`() = withTrain { train ->
        val original = CarControlConfig(mass = 30_000.0)
        val control = TrainControlConfig()
        val controller = RealisticManualController(train, control, listOf(original), 9.81)
        train.setControllerForTest(controller)
        controller.setHandle("P1")
        val replacement = CarControlConfig(mass = 40_000.0)
        val update = prepareTrainConfiguration(train, control.copy(curveSpeedLimitOffset = 2.0), listOf(replacement))
        assertSame(original, controller.dynamicsProfileFor(train.cars.single()))
        train.world.onOwnerForTest { update.install(train) }
        assertSame(controller, train.controller)
        assertSame(replacement, controller.dynamicsProfileFor(train.cars.single()))
        assertEquals("P1", controller.handle)
        assertEquals(2.0, train.world.basicWorldData.trainSpeedProfiles.get(train.id)!!.curveSpeedLimitOffsetMetersPerSecond)
        assertThrows<IllegalArgumentException> { prepareTrainConfiguration(train, control, emptyList()) }
        assertSame(replacement, controller.dynamicsProfileFor(train.cars.single()))
    }

    @Test
    fun `geometry changes reject an existing instance before model and physics diverge`() = withTrain { train ->
        val car = train.cars.single()
        val definition = CarDefinition("test/head", length = car.length, floorHeight = car.floorHeight,
            connectorLengthA = car.connectorLengthAtA, connectorLengthB = car.connectorLengthAtB,
            bogieAOffset = car.bogieAOffset, bogieBOffset = car.bogieBOffset)
        requireCompatibleGeometry(car, definition)
        assertThrows<IllegalArgumentException> { requireCompatibleGeometry(car, definition.copy(length = 11.0)) }
        assertThrows<IllegalArgumentException> { requireCompatibleGeometry(car, definition.copy(bogieAOffset = 1.0)) }
        assertEquals(10.0, car.length)
    }

    private fun withTrain(action: (CoreTrain) -> Unit) {
        val world = createTestWorld()
        val basic = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        world.registerExtensionData(basic, BasicWorldData())
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
                    action(createTrainWithConfig("resources", rail, world, listOf(10.0)))
                }
            }
        }
    }
}
