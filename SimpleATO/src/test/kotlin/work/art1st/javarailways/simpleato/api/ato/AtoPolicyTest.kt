package work.art1st.javarailways.simpleato.api.ato

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.constraint.ConstraintKey
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwnerType
import work.art1st.javarailways.simpleato.api.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.FollowConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.simpleato.api.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.StopConstraint
import kotlin.math.sqrt

class AtoPolicyTest {
    private val profile = AtoSafetyProfile(reactionTimeSeconds = 0.0, stopToleranceMeters = 0.0)

    @Test
    fun `distance constraints forward current speed and reaction once to the shared envelope`() {
        val trainProfile = object : TrainDynamics.TrainProfile {
            override val maxSpeed = 30.0
            override val powerHandles = listOf("P1")
            override val brakeHandles = listOf("B1")
            override val brakeApplyTime = 0.0
            override val brakeReleaseTime = 0.0
            override val adhesionCoefficient = 1.0
            override val tractionJerk = 2.0
            override val brakeJerk = 2.0
            override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
            override fun normalizedBrakeScale(handle: String) = if (handle == "B1") 1.0 else 0.0
            override fun resistanceForceAt(speed: Double) = 0.0
        }
        val carProfile = object : TrainDynamics.CarProfile {
            override val mass = 10_000.0
            override val effectiveMass = mass
            override val motorAdhesionMassRatio = 1.0
            override val brakeAdhesionMassRatio = 1.0
            override val brakeMaxForce = 20_000.0
            override fun motorForceAt(speed: Double) = 10_000.0
            override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
        }
        val envelope = GradeAwareBrakingEnvelope.fromDynamics(trainProfile, listOf(carProfile), 9.81,
            listOf(BrakingGradeSegment(100.0, -0.02)), 1.2)
        val safety = AtoSafetyProfile(reactionTimeSeconds = 0.4, safetyDistanceMeters = 5.0,
            stopToleranceMeters = 0.25)
        val key = ConstraintKey("test", "reaction")
        listOf(
            StopConstraint(key, 20.25) to 0.0,
            MovementAuthorityConstraint(key, 25.0) to 0.0,
            FollowConstraint(key, 25.0, 2.0) to 2.0,
            RouteSpeedLimitConstraint(key, 20.0, 2.0) to 2.0
        ).forEach { (constraint, target) ->
            assertEquals(envelope.permittedSpeedMetersPerSecond(target, 20.0, 4.0, 0.4),
                constraint.permittedSpeedMetersPerSecond(4.0, safety, envelope), 1.0e-12,
                constraint.javaClass.simpleName)
        }
    }

    @Test
    fun `stop constraint creates a braking curve instead of an immediate zero limit`() {
        val constraint = StopConstraint(ConstraintKey("test", "stop"), remainingDistanceMeters = 50.0)

        assertEquals(
            sqrt(100.0),
            constraint.permittedSpeedMetersPerSecond(10.0, profile),
            1.0e-9
        )
        assertEquals(0.0, constraint.copy(remainingDistanceMeters = 0.0)
            .permittedSpeedMetersPerSecond(10.0, profile))
    }

    @Test
    fun `stop constraint can override the profile stopping margin for precise berthing`() {
        val berthProfile = AtoSafetyProfile(reactionTimeSeconds = 0.0, stopToleranceMeters = 0.5)
        val defaultStop = StopConstraint(
            ConstraintKey("test", "default-stop"),
            remainingDistanceMeters = 0.25
        )
        val preciseStop = StopConstraint(
            ConstraintKey("test", "precise-stop"),
            remainingDistanceMeters = 0.25,
            stoppingMarginMeters = 0.0
        )

        assertEquals(0.0, defaultStop.permittedSpeedMetersPerSecond(0.0, berthProfile))
        assertTrue(preciseStop.permittedSpeedMetersPerSecond(0.0, berthProfile) > 0.0)
    }

    @Test
    fun `route speed constraint creates a braking curve before the lower limit`() {
        val constraint = RouteSpeedLimitConstraint(
            ConstraintKey("test", "route-speed"),
            distanceToStartMeters = 50.0,
            maximumSpeedMetersPerSecond = 5.0
        )

        assertEquals(
            sqrt(125.0),
            constraint.permittedSpeedMetersPerSecond(10.0, profile),
            1.0e-9
        )
    }

    @Test
    fun `owner replacement is atomic and minimum constraint wins`() {
        val manager = ConstraintManager()
        val owner = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "test")
        manager.replace(
            owner,
            listOf(CurrentSpeedLimitConstraint(ConstraintKey("test", "slow"), 8.0))
        )
        assertEquals(8.0, manager.permittedSpeedMetersPerSecond(0.0, 20.0, profile))

        manager.replace(
            owner,
            listOf(CurrentSpeedLimitConstraint(ConstraintKey("test", "slower"), 5.0))
        )
        assertEquals(5.0, manager.permittedSpeedMetersPerSecond(0.0, 20.0, profile))
        assertEquals(1, manager.snapshot().getValue(owner).size)
    }

    @Test
    fun `world coordinators stall independently`() {
        val first = AtoWorldCoordinator("first", reportSink = {})
        val second = AtoWorldCoordinator("second", reportSink = {})
        val report = AtoStallReport(
            worldId = "first",
            trainId = "T1",
            tick = 1L,
            errorType = "test",
            message = "failure",
            stackTrace = "stack",
            initialTrain = null,
            currentTrain = null,
            route = null,
            perception = null,
            constraints = emptyMap()
        )

        assertTrue(first.stall(report))
        assertTrue(first.isStalled)
        assertTrue(first.safetyInterlock.isEngaged)
        assertFalse(second.isStalled)

        first.reset()
        assertFalse(first.isStalled)
        assertFalse(first.safetyInterlock.isEngaged)
        assertEquals(null, first.lastStall)
    }

    @Test
    fun `stall report JSON contains machine-readable failure details`() {
        val report = AtoStallReport(
            worldId = "world-id",
            trainId = "T1",
            tick = 7L,
            errorType = "test.Failure",
            message = "broken",
            stackTrace = "stack",
            initialTrain = null,
            currentTrain = null,
            route = RouteSnapshot("test:route", 4L, 9L, emptyList()),
            perception = null,
            constraints = emptyMap(),
            occurredAtEpochMillis = 123L
        )

        val root = Json.parseToJsonElement(AtoStallReportJson.encode(report)).jsonObject

        assertEquals(3, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertFalse(root.containsKey("activeProviderId"))
        assertEquals("world-id", root.getValue("worldId").jsonPrimitive.content)
        assertEquals("broken", root.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals(123L, root.getValue("occurredAtEpochMillis").jsonPrimitive.content.toLong())
        assertEquals(
            9L,
            root.getValue("route").jsonObject.getValue("topologyRevision").jsonPrimitive.content.toLong()
        )
    }
}
