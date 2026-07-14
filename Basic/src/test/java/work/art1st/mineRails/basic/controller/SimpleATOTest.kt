package work.art1st.mineRails.basic.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.ato.constraint.AtoConstraint
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintKey
import work.art1st.mineRails.basic.api.ato.constraint.CurrentSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.provider.AtoProvider
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderBoundary
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderContext
import work.art1st.mineRails.basic.api.ato.provider.ProviderBoundaryAction
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.AsyncTickTestSupport

class SimpleATOTest {
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `rail without configured limit uses actuator maximum speed`() {
        val fixture = fixture("max-speed")

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
        assertEquals(fixture.train.id, fixture.data.dispatcher.initialTrain(fixture.train.id)?.trainId)
    }

    @Test
    fun `following constraint holds behind a nearby stopped train`() {
        val fixture = fixture("following")
        val front = createTrainWithConfig("front", fixture.train.head.location.rail, fixture.world, listOf(10.0))
        front.setLocation(fixture.train.head.location.forward(11.0)!!)
        front.commit()

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)

        front.setLocation(fixture.train.head.location.forward(200.0)!!)
        front.commit()
        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `stopAt brakes to the observed RailObject`() {
        val fixture = fixture("stop-target")
        val head = fixture.train.head.location
        val targetArc = head.arcLength + 20.0
        val target = object : RailObject {
            override val ref = RailObjectRef("test", "stop-target")
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(head.railView, targetArc)
        }
        fixture.data.railObjects.register(target)
        fixture.ato.stopAt("station", target)

        repeat(600) { tickAndPublish(fixture.ato, 50.0) }

        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertEquals(targetArc, fixture.train.head.location.arcLength, 0.4)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `unknown provider stalls all ATO in its world only`() {
        val fixture = fixture("provider-failure")
        val otherWorld = AtoWorldCoordinator("other", reportSink = {})
        val secondTrain = createTrainWithConfig(
            "same-world-second",
            fixture.train.head.location.rail,
            fixture.world,
            listOf(10.0)
        )
        secondTrain.setLocation(fixture.train.head.location.forward(100.0)!!)
        secondTrain.commit()
        val secondAto = SimpleATO(
            secondTrain,
            fixture.world,
            KinematicTrainActuator(10.0, profile),
            profile
        )
        secondTrain.setController(secondAto)
        tickAndPublish(secondAto, 1_000.0)
        assertEquals(1.0, secondTrain.speed, 1.0e-9)

        fixture.ato.setProvider("missing")
        tickAndPublish(fixture.ato, 50.0)
        tickAndPublish(secondAto, 50.0)

        assertTrue(fixture.coordinator.isStalled)
        assertFalse(otherWorld.isStalled)
        assertEquals(0.0, fixture.train.speed)
        assertEquals(0.0, secondTrain.speed)
        assertTrue(fixture.coordinator.lastStall?.message?.contains("not registered") == true)
    }

    @Test
    fun `passing provider boundary activates its constraints`() {
        val fixture = fixture("provider-boundary")
        fixture.data.atoProviders.register("stop-provider") {
            object : AtoProvider {
                override val id: String = "stop-provider"

                override fun constraints(context: AtoProviderContext): Collection<AtoConstraint> = listOf(
                    CurrentSpeedLimitConstraint(ConstraintKey("test", "provider-stop"), 0.0)
                )
            }
        }
        val head = fixture.train.head.location
        val boundary = AtoProviderBoundary(
            ref = RailObjectRef("test", "provider-boundary"),
            location = RailLocationWithFacing(head.railView, head.arcLength + 0.5),
            providerId = "stop-provider",
            action = ProviderBoundaryAction.ACTIVATE,
            direction = head.facing
        )
        fixture.data.railObjects.register(boundary)

        tickAndPublish(fixture.ato, 1_000.0)
        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        repeat(3) { tickAndPublish(fixture.ato, 1_000.0) }

        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    private fun fixture(id: String): Fixture {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(500.0, segmentCount = 1)
        )
        val train = createTrainWithConfig(id, rail, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("world-$id", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val ato = SimpleATO(
            train = train,
            world = world,
            actuator = KinematicTrainActuator(10.0, profile),
            safetyProfile = profile
        )
        train.setController(ato)
        return Fixture(world, train, ato, coordinator, data)
    }

    private fun tickAndPublish(ato: SimpleATO, deltaMillis: Double) {
        AsyncTickTestSupport.run { ato.tick(deltaMillis) }
        ato.commit()
    }

    private data class Fixture(
        val world: work.art1st.mineRails.core.physics.CoreWorld,
        val train: work.art1st.mineRails.core.physics.train.CoreTrain,
        val ato: SimpleATO,
        val coordinator: AtoWorldCoordinator,
        val data: BasicWorldData
    )
}
