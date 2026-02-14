package work.art1st.mineRails.core.physics.rail.curve

import work.art1st.mineRails.core.api.physics.curve.Curve
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * 测试 PrimeCurve.getIndexAt 的正确性。
 * 通过生成直线，调用 getPointAt(arcLength)，检查返回的 point.arcLength 与传入 arcLength 的差值。
 */
class PrimeCurveTest {

    private val tolerance = Curve.TOLERANCE // 5e-3

    @Test
    fun `test getPointAt on straight line - boundary values`() {
        val length = 100.0
        val segments = PrimeCurveGenerator.buildStraight(length, segmentCount = 1)
        val curve = segments.first()

        // 测试边界值：0.0 和 totalArcLength
        val point0 = curve.getPointAt(0.0)
        assertNotNull(point0, "getPointAt(0.0) should not be null")
        assertEquals(0.0, point0!!.arcLength, tolerance, "point at arcLength 0.0 should have arcLength = 0.0")

        val totalArcLength = curve.totalArcLength
        val pointEnd = curve.getPointAt(totalArcLength)
        assertNotNull(pointEnd, "getPointAt(totalArcLength) should not be null")
        assertEquals(totalArcLength, pointEnd!!.arcLength, tolerance, "point at totalArcLength should have arcLength = totalArcLength")
    }

    @Test
    fun `test getPointAt on straight line - middle values`() {
        val length = 100.0
        val segments = PrimeCurveGenerator.buildStraight(length, segmentCount = 1)
        val curve = segments.first()
        val totalArcLength = curve.totalArcLength

        // 测试中间值：25%, 50%, 75% 位置
        val testArcLengths = listOf(
            totalArcLength * 0.25,
            totalArcLength * 0.5,
            totalArcLength * 0.75
        )

        for (arcLength in testArcLengths) {
            val point = curve.getPointAt(arcLength)
            assertNotNull(point, "getPointAt($arcLength) should not be null")
            val diff = abs(point!!.arcLength - arcLength)
            assertTrue(diff <= tolerance, 
                "For arcLength=$arcLength, got point.arcLength=${point.arcLength}, difference=$diff exceeds tolerance=$tolerance")
        }
    }

    @Test
    fun `test getPointAt on straight line - multiple random values`() {
        val length = 100.0
        val segments = PrimeCurveGenerator.buildStraight(length, segmentCount = 1)
        val curve = segments.first()
        val totalArcLength = curve.totalArcLength

        // 测试多个随机值，覆盖整个范围
        val testCount = 50
        for (i in 0 until testCount) {
            val arcLength = (totalArcLength * i) / (testCount - 1)
            val point = curve.getPointAt(arcLength)
            assertNotNull(point, "getPointAt($arcLength) should not be null")
            val diff = abs(point!!.arcLength - arcLength)
            assertTrue(diff <= tolerance,
                "For arcLength=$arcLength, got point.arcLength=${point.arcLength}, difference=$diff exceeds tolerance=$tolerance")
        }
    }

    @Test
    fun `test getPointAt on straight line - values between points`() {
        val length = 100.0
        val segments = PrimeCurveGenerator.buildStraight(length, segmentCount = 1, step = 1.0)
        val curve = segments.first()
        val totalArcLength = curve.totalArcLength

        // 测试正好在两个点之间的值（例如 0.5, 1.5, 2.5 等）
        // 这些值应该返回最接近的点
        val step = 1.0
        val testArcLengths = (0 until (totalArcLength / step).toInt()).map { 
            (it + 0.5) * step 
        }

        for (arcLength in testArcLengths) {
            if (arcLength > totalArcLength) break
            val point = curve.getPointAt(arcLength)
            assertNotNull(point, "getPointAt($arcLength) should not be null")
            // 对于中间值，差值应该不超过步长的一半
            val expectedMaxDiff = step / 2.0
            val diff = abs(point!!.arcLength - arcLength)
            assertTrue(diff <= expectedMaxDiff.coerceAtMost(tolerance),
                "For arcLength=$arcLength (between points), got point.arcLength=${point.arcLength}, difference=$diff exceeds expected max=$expectedMaxDiff")
        }
    }

    @Test
    fun `test getPointAt on straight line - near boundary`() {
        val length = 100.0
        val segments = PrimeCurveGenerator.buildStraight(length, segmentCount = 1)
        val curve = segments.first()
        val totalArcLength = curve.totalArcLength

        // 测试接近边界的小值
        val smallValues = listOf(0.001, 0.01, 0.1, tolerance / 2)
        for (arcLength in smallValues) {
            if (arcLength > totalArcLength) break
            val point = curve.getPointAt(arcLength)
            assertNotNull(point, "getPointAt($arcLength) should not be null")
            val diff = abs(point!!.arcLength - arcLength)
            assertTrue(diff <= tolerance,
                "For small arcLength=$arcLength, got point.arcLength=${point.arcLength}, difference=$diff exceeds tolerance=$tolerance")
        }

        // 测试接近边界的大值
        val largeValues = listOf(
            totalArcLength - tolerance / 2,
            totalArcLength - 0.1,
            totalArcLength - 0.01,
            totalArcLength - 0.001
        )
        for (arcLength in largeValues) {
            if (arcLength < 0) continue
            val point = curve.getPointAt(arcLength)
            assertNotNull(point, "getPointAt($arcLength) should not be null")
            val diff = abs(point!!.arcLength - arcLength)
            assertTrue(diff <= tolerance,
                "For large arcLength=$arcLength (near totalArcLength=$totalArcLength), got point.arcLength=${point.arcLength}, difference=$diff exceeds tolerance=$tolerance")
        }
    }
}
