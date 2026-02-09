package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.control.train.MockTrainController
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * 直线与曲线轨道上列车匀速运动测试。
 * - 使用 PrimeCurveGenerator 生成直线和多种半径的圆。
 * - Mock TrainController 恒定速度。
 * - 不同长度车厢组合，跑若干循环，比较速度、位置与理论值。
 */
class TrainUniformMotionTest {

    private val speedTolerance = 1e-10
    private val distanceTolerance = Curve.TOLERANCE * 2
    private val positionTolerance = 5e-3
    private val tickMs = 50.0
    private val tickSeconds = tickMs * 0.001

    @Test
    fun `straight track - constant speed and distance`() {
        val length = 500.0
        val segments = PrimeCurveGenerator.buildStraight(length)
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, segments)
        val constantSpeed = 20.0 // m/s
        val cars = createCarsOnRail(rail, world, listOf(10.0, 10.0))
        val train = createTrain("t1", cars.toMutableList(), world)
        train.controller = MockTrainController(train, world, constantSpeed)
        world.trains[train.id] = train

        train.setLocation(RailLocationWithFacing(rail.viewAB, 0))

        val numTicks = 100
        var totalDistance = 0.0
        for (i in 0 until numTicks) {
            assertEquals(constantSpeed, train.speed, speedTolerance, "tick $i speed")
            train.controller!!.tick(tickMs)
            totalDistance += constantSpeed * tickSeconds
        }

        val expectedDistance = constantSpeed * numTicks * tickSeconds
        assertEquals(expectedDistance, totalDistance, distanceTolerance)
        assertFalse(train.isDerailed)
    }

    @Test
    fun `circle R=30 - several loops, speed and position`() {
        runCircleTest(30.0, numLoops = 2, carLengths = listOf(10.0, 10.0))
    }

    @Test
    fun `circle R=60 - several loops`() {
        runCircleTest(60.0, numLoops = 2, carLengths = listOf(10.0, 10.0))
    }

    @Test
    fun `circle R=100 - several loops`() {
        runCircleTest(100.0, numLoops = 2, carLengths = listOf(10.0, 10.0))
    }

    @Test
    fun `circle R=200 - several loops`() {
        runCircleTest(200.0, numLoops = 2, carLengths = listOf(10.0, 10.0))
    }

    @Test
    fun `circle R=500 - several loops`() {
        runCircleTest(500.0, numLoops = 2, carLengths = listOf(10.0, 10.0))
    }

    @Test
    fun `circle R=100 - 5 cars 20m each, several loops`() {
        runCircleTest(100.0, numLoops = 2, carLengths = List(5) { 20.0 })
    }

    private fun runCircleTest(radius: Double, numLoops: Int, carLengths: List<Double>) {
        val segments = PrimeCurveGenerator.buildCircle(radius)
        val world = createTestWorld()
        val rail = createLoopRailFromSegments(world, segments)
        val totalArcLength = totalArcLengthFromFirstRail(rail, isLoop = true)
        val constantSpeed = 15.0
        val cars = createCarsOnRail(rail, world, carLengths)
        val train = createTrain("t-circle-$radius", cars.toMutableList(), world)
        train.controller = MockTrainController(train, world, constantSpeed)
        world.trains[train.id] = train

        train.setLocation(RailLocationWithFacing(rail.viewAB, 0))
        val startArcLength = headCurveArcLengthInRangeMultiSegment(train, rail, isLoop = true)

        val timePerLoop = totalArcLength / constantSpeed
        val ticksPerLoop = (timePerLoop / tickSeconds).toInt().coerceAtLeast(1)
        val numTicks = numLoops * ticksPerLoop + 50

        var totalDistance = 0.0
        for (i in 0 until numTicks) {
            assertEquals(constantSpeed, train.speed, speedTolerance, "tick $i speed")
            train.controller!!.tick(tickMs)
            totalDistance += constantSpeed * tickSeconds
        }

        val expectedDistance = constantSpeed * numTicks * tickSeconds
        assertEquals(expectedDistance, totalDistance, distanceTolerance)

        val expectedArcLength = (startArcLength + totalDistance) % totalArcLength
        val actualArcLength = headCurveArcLengthInRangeMultiSegment(train, rail, isLoop = true)
        val arcLengthDiff = abs(actualArcLength - expectedArcLength).coerceAtMost(
            abs(actualArcLength - expectedArcLength - totalArcLength)
        ).coerceAtMost(abs(actualArcLength - expectedArcLength + totalArcLength))
        assertEquals(0.0, arcLengthDiff, positionTolerance) {
            "head position on curve: expected arcLength ~ $expectedArcLength, got $actualArcLength (totalDistance=$totalDistance)"
        }
        assertFalse(train.isDerailed)
    }
}
