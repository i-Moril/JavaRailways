package work.art1st.javarailways.simplesignals.presentation

import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import kotlin.math.sqrt

class ModelPlacementCalculatorTest {
    private val railId = UUID.randomUUID()
    private val identity = ModelPose.IDENTITY
    private val epsilon = 1e-10

    @Test
    fun `world positive Z heading preserves model XYZ axes and BA reverses X and Z`() {
        val curve = line(Vector3d(10.0, 20.0, 30.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0))
        val adjustment = ModelPose(1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0)
        val ab = ModelPlacementCalculator.calculate(placement(adjustment = adjustment), curve)
        val ba = ModelPlacementCalculator.calculate(placement(ViewDirection.BA, adjustment = adjustment), curve)
        assertPosition(ab, 11.0, 22.0, 35.0)
        assertPosition(ba, 9.0, 22.0, 29.0)
        assertAxes(ab, Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), Vector3d(-1.0, 0.0, 0.0))
        assertAxes(ba, Vector3d(0.0, 0.0, -1.0), Vector3d(0.0, 1.0, 0.0), Vector3d(1.0, 0.0, 0.0))
    }

    @Test
    fun `flat AB and BA offsets use directed lateral up and forward at the same canonical arc`() {
        val curve = line(Vector3d(10.0, 20.0, 30.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0))
        val ab = placement(adjustment = ModelPose(-3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val ba = ab.copy(position = ab.position.copy(facing = ViewDirection.BA))
        val abPose = ModelPlacementCalculator.calculate(ab, curve)
        val baPose = ModelPlacementCalculator.calculate(ba, curve)

        assertPosition(abPose, 13.0, 22.0, 33.0)
        assertPosition(baPose, 11.0, 22.0, 27.0)
        assertAxes(abPose, Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))
        assertAxes(baPose, Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, -1.0))
        assertEquals(ab.adjustment, ba.adjustment)
        assertEquals(ab.position.arcLengthInAbMeters, ba.position.arcLengthInAbMeters)
    }

    @Test
    fun `upright placement removes combined slope and cant at an arbitrary heading`() {
        val curve = bankedSlope()
        val adjustment = ModelPose(-3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val ab = ModelPlacementCalculator.calculate(placement(adjustment = adjustment), curve)
        val ba = ModelPlacementCalculator.calculate(placement(ViewDirection.BA, adjustment = adjustment), curve)

        assertPosition(ab, 9.16, 23.2, 33.88)
        assertAxes(ab, Vector3d(0.6, 0.0, 0.8), Vector3d(0.0, 1.0, 0.0), Vector3d(-0.8, 0.0, 0.6))
        assertPosition(ba, 12.76, 23.2, 28.68)
        assertAxes(ba, Vector3d(-0.6, 0.0, -0.8), Vector3d(0.0, 1.0, 0.0), Vector3d(0.8, 0.0, -0.6))
    }

    @Test
    fun `explicit tilt follows combined slope and cant including BA handedness`() {
        val curve = bankedSlope()
        val c = sqrt(3.0) / 2.0 // 60 degree cant, with heading cos=0.6 sin=0.8 and grade sin=0.6.
        val forward = Vector3d(0.48, 0.6, 0.64)
        val up = Vector3d(-0.18 - 0.8 * c, 0.4, -0.24 + 0.6 * c)
        val right = Vector3d(-0.4 + 0.36 * c, -0.8 * c, 0.3 + 0.48 * c)
        val adjustment = ModelPose(-3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val ab = ModelPlacementCalculator.calculate(placement(adjustment = adjustment, tilt = true), curve)
        assertPosition(ab, 9.88 - 0.52 * c, 22.6 - 2.4 * c, 32.34 + 2.64 * c)
        assertAxes(ab, forward, up, right)
        val ba = ModelPlacementCalculator.calculate(placement(ViewDirection.BA, adjustment = adjustment, tilt = true), curve)
        assertPosition(ba, 11.32 - 2.68 * c, 21.4 + 2.4 * c, 29.26 - 0.24 * c)
        assertAxes(ba, Vector3d(-0.48, -0.6, -0.64), up, Vector3d(0.4 - 0.36 * c, 0.8 * c, -0.3 - 0.48 * c))
    }

    @Test
    fun `local rotation is composed after a non axis aligned frame and leaves offsets in place`() {
        val curve = bankedSlope()
        val base = placement(adjustment = ModelPose(-3.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0), tilt = true)
        val turned = base.copy(adjustment = ModelPose(-3.0, 2.0, 1.0, -sqrt(0.5), 0.0, 0.0, sqrt(0.5)))
        val before = ModelPlacementCalculator.calculate(base, curve)
        val after = ModelPlacementCalculator.calculate(turned, curve)
        val c = sqrt(3.0) / 2.0

        assertPosition(after, before.x(), before.y(), before.z())
        // Local -90 degrees about X maps Z (forward) to Y and Y to -Z before the rail basis.
        assertAxes(after,
            Vector3d(-0.18 - 0.8 * c, 0.4, -0.24 + 0.6 * c),
            Vector3d(-0.48, -0.6, -0.64),
            Vector3d(-0.4 + 0.36 * c, -0.8 * c, 0.3 + 0.48 * c))
    }

    @Test
    fun `accepted unit tolerance is preserved across rotation composition`() {
        for (sign in listOf(-1.0, 1.0)) {
            val scale = sqrt(1.0 + sign * (ModelPose.UNIT_LENGTH_TOLERANCE - 1e-15))
            val component = sqrt(0.5) * scale
            val adjustment = ModelPose(-3.0, 2.0, 1.0, -component, 0.0, 0.0, component)
            val input = placement(adjustment = adjustment, tilt = true)
            val pose = ModelPlacementCalculator.calculate(input, bankedSlope())
            val c = sqrt(3.0) / 2.0
            assertAxes(pose,
                Vector3d(-0.18 - 0.8 * c, 0.4, -0.24 + 0.6 * c),
                Vector3d(-0.48, -0.6, -0.64),
                Vector3d(-0.4 + 0.36 * c, -0.8 * c, 0.3 + 0.48 * c))
            assertEquals(component, input.adjustment.rotationW())
        }
    }

    @Test
    fun `real curve endpoints remain A and B regardless of facing`() {
        val curve = bankedSlope()
        for (facing in ViewDirection.entries) {
            val start = ModelPlacementCalculator.calculate(placement(facing, arc = 0.0, tilt = true), curve)
            val end = ModelPlacementCalculator.calculate(placement(facing, arc = 10.0, tilt = true), curve)
            assertPosition(start, 10.0, 20.0, 30.0)
            assertPosition(end, 14.8, 26.0, 36.4)
        }
    }

    @Test
    fun `real discrete interpolation is sampled once and forward offset is local not more arc length`() {
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(3.0, 0.0, 4.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 5.0),
            Curve.RawPoint(Vector3d(7.0, 0.0, 7.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0)
        ))
        val sampledArcs = mutableListOf<Double>()
        val observed = object : Curve by curve {
            override fun getPointAt(arcLength: Double): Curve.Point {
                sampledArcs.add(arcLength)
                return curve.getPointAt(arcLength)
            }
        }
        val pose = ModelPlacementCalculator.calculate(placement(arc = 2.5,
            adjustment = ModelPose(0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)), observed)

        assertEquals(listOf(2.5), sampledArcs)
        assertPosition(pose, 1.5 + sqrt(2.0), 0.0, 2.0 + sqrt(2.0))
        assertAxes(pose, Vector3d(sqrt(0.5), 0.0, sqrt(0.5)), Vector3d(0.0, 1.0, 0.0),
            Vector3d(-sqrt(0.5), 0.0, sqrt(0.5)))
    }

    @Test
    fun `calculation never mutates vectors returned by the curve`() {
        val curve = bankedSlope()
        val endpoint = curve.getPointAt(10.0)
        val before = listOf(Vector3d(endpoint.position), Vector3d(endpoint.tangent), Vector3d(endpoint.normal))
        for (facing in ViewDirection.entries) {
            for (tilt in listOf(false, true)) {
                ModelPlacementCalculator.calculate(placement(facing, 10.0,
                    ModelPose(4.0, 5.0, 6.0, 0.0, 0.0, 0.0, 1.0), tilt), curve)
            }
        }
        val after = curve.getPointAt(10.0)
        assertEquals(before, listOf(after.position, after.tangent, after.normal))
    }

    @Test
    fun `ground calculation requires only an absolute pose`() {
        val pose = ModelPose(-100.0, 63.5, 87.0, 0.0, sqrt(0.5), 0.0, sqrt(0.5))
        assertSame(pose, ModelPlacementCalculator.calculate(ModelPlacement.Ground(pose)))
    }

    @Test
    fun `missing curve and out of range arc fail with placement context`() {
        val anchor = placement()
        val missing = assertThrows(IllegalArgumentException::class.java) {
            ModelPlacementCalculator.calculate(anchor, null)
        }
        assertTrue(missing.message!!.contains(railId.toString()))
        assertTrue(missing.message!!.contains("curve is missing"))
        val outside = assertThrows(IllegalArgumentException::class.java) {
            ModelPlacementCalculator.calculate(placement(arc = 10.000001), bankedSlope())
        }
        assertTrue(outside.message!!.contains("outside [0, 10.0]"))
    }

    @Test
    fun `non finite curve lengths and unusable sampled geometry reject without a pose`() {
        val curve = bankedSlope()
        for (length in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val invalid = object : Curve by curve { override val totalArcLength = length }
            assertThrows(IllegalArgumentException::class.java) { ModelPlacementCalculator.calculate(placement(), invalid) }
        }
        val point = curve.getPointAt(10.0)
        val badPoints = listOf(
            point.copy(position = Vector3d(Double.NaN, 0.0, 0.0)),
            point.copy(tangent = Vector3d(Double.POSITIVE_INFINITY, 0.0, 0.0)),
            point.copy(tangent = Vector3d()),
            point.copy(normal = Vector3d(Double.NaN, 0.0, 0.0)),
            point.copy(normal = Vector3d()),
            point.copy(normal = point.tangent)
        )
        for (badPoint in badPoints) {
            val invalid = object : Curve by curve {
                override fun getPointAt(arcLength: Double) = badPoint
            }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ModelPlacementCalculator.calculate(placement(arc = 10.0, tilt = true), invalid)
            }
            assertTrue(failure.message!!.contains(railId.toString()))
        }
    }

    @Test
    fun `vertical tangent rejects upright frame but can follow a valid tilted frame`() {
        val curve = line(Vector3d(), Vector3d(0.0, 1.0, 0.0), Vector3d(-1.0, 0.0, 0.0))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ModelPlacementCalculator.calculate(placement(), curve)
        }
        assertTrue(failure.message!!.contains("horizontal tangent"))
        val tilted = ModelPlacementCalculator.calculate(placement(tilt = true), curve)
        assertAxes(tilted, Vector3d(0.0, 1.0, 0.0), Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0))
    }

    @Test
    fun `finite offsets that overflow the world position are rejected`() {
        val curve = line(Vector3d(Double.MAX_VALUE, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ModelPlacementCalculator.calculate(placement(arc = 10.0,
                adjustment = ModelPose(0.0, 0.0, Double.MAX_VALUE, 0.0, 0.0, 0.0, 1.0)), curve)
        }
        assertTrue(failure.message!!.contains("offset produces a non-finite position"))
    }

    private fun placement(
        facing: ViewDirection = ViewDirection.AB,
        arc: Double = 2.0,
        adjustment: ModelPose = identity,
        tilt: Boolean = false
    ) = ModelPlacement.RailAttached(RailPositionSnapshot(railId, facing, arc), adjustment, tilt)

    private fun bankedSlope(): PrimeCurve {
        val c = sqrt(3.0) / 2.0
        return line(Vector3d(10.0, 20.0, 30.0), Vector3d(0.48, 0.6, 0.64),
            Vector3d(-0.18 - 0.8 * c, 0.4, -0.24 + 0.6 * c))
    }

    private fun line(origin: Vector3dc, forward: Vector3dc, up: Vector3dc): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(origin, forward, up, 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(origin).fma(10.0, forward), forward, up, 10.0, Curve.SegmentType.LINE_END)
    ))

    private fun assertPosition(pose: ModelPose, x: Double, y: Double, z: Double) {
        assertEquals(x, pose.x(), epsilon)
        assertEquals(y, pose.y(), epsilon)
        assertEquals(z, pose.z(), epsilon)
    }

    private fun assertAxes(pose: ModelPose, forward: Vector3dc, up: Vector3dc, right: Vector3dc) {
        val rotation = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
        val expected = listOf(Vector3d(right).negate(), up, forward)
        val actual = listOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))
            .map(rotation::transform)
        expected.zip(actual).forEach { (want, got) ->
            assertEquals(want.x(), got.x, epsilon)
            assertEquals(want.y(), got.y, epsilon)
            assertEquals(want.z(), got.z, epsilon)
        }
        assertEquals(1.0, rotation.lengthSquared(), epsilon)
    }
}
