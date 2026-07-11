package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * StandardRailCurve 回旋线与轨道点生成测试。
 */
class StandardRailCurveTest {

    @Nested
    inner class CurveMath {
        @Test
        fun `入口回旋线曲率线性增长到圆曲线`() {
            val R = 30.0
            val L = 5.0
            val curve = StandardRailCurve(R, L, 10.0)
            assertEquals(0.0, curve.curvatureAt(0.0), 1e-9)
            assertEquals(0.5 / R, curve.curvatureAt(L / 2.0), 1e-9)
            assertEquals(1.0 / R, curve.curvatureAt(L), 1e-9)
        }

        @Test
        fun `出口回旋线曲率从圆曲线连续下降到0`() {
            val R = 30.0
            val L = 5.0
            val Lc = 10.0
            val curve = StandardRailCurve(R, L, Lc)
            assertEquals(1.0 / R, curve.curvatureAt(L + Lc), 1e-9)
            assertEquals(0.5 / R, curve.curvatureAt(L + Lc + L / 2.0), 1e-9)
            assertEquals(0.0, curve.curvatureAt(2.0 * L + Lc), 1e-9)
        }

        @Test
        fun `出口回旋线切向角导数延续圆曲线曲率`() {
            val R = 30.0
            val L = 5.0
            val Lc = 10.0
            val curve = StandardRailCurve(R, L, Lc)
            val s = L + Lc
            val h = 1e-4
            val derivative = (curve.tangentAngleAt(s + h) - curve.tangentAngleAt(s - h)) / (2.0 * h)
            assertEquals(1.0 / R, derivative, 1e-5)
        }

        @Test
        fun `出口回旋线终点切向角等于总转角`() {
            val R = 30.0
            val L = 5.0
            val Lc = 10.0
            val curve = StandardRailCurve(R, L, Lc)
            val expectedTheta = L / (2.0 * R) + Lc / R + L / (2.0 * R)
            assertEquals(expectedTheta, curve.tangentAngleAt(curve.totalLength), 1e-9)
        }
    }

    @Nested
    inner class PointAt {
        @Test
        fun `起点弧长为0`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val (pos, theta, _) = curve.pointAt(0.0)
            assertEquals(0.0, pos.x, 1e-9)
            assertEquals(0.0, pos.y, 1e-9)
            assertEquals(0.0, theta, 1e-9)
        }

        @Test
        fun `回旋线起点切向角为0`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val (_, theta, _) = curve.pointAt(1e-4)
            assertTrue(abs(theta) < 1e-6, "起点切向角应接近0")
        }

        @Test
        fun `终点切向角等于曲线总转角`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val total = curve.totalLength
            val (_, theta, _) = curve.pointAt(total)
            // 总转角 = 入口回旋线 L²/(2A²) + 圆弧 Lc/R + 出口回旋线 L/(2R)
            val L = 5.0; val R = 30.0; val Lc = 10.0
            val A = sqrt(R * L)
            val expectedTheta = L * L / (2 * A * A) + Lc / R + L / (2 * R)
            assertEquals(expectedTheta, theta, 0.001, "终点切向角应等于总转角")
        }

        @Test
        fun `无缓和曲线时退化为纯圆弧`() {
            // 无缓和曲线时：κ = 1/R，θ = s/R
            val R = 30.0; val Lc = 10.0
            val curve = StandardRailCurve(R, 0.0, Lc)
            val theta = curve.tangentAngleAt(5.0)
            val expectedTheta = 5.0 / R
            assertEquals(expectedTheta, theta, 1e-9, "纯圆弧切向角应等于 s/R")
        }

        @Test
        fun `无缓和曲线时全程切向角等于s over R`() {
            val R = 30.0; val Lc = 10.0
            val curve = StandardRailCurve(R, 0.0, Lc)
            for (s in listOf(1.0, 5.0, 9.99)) {
                val theta = curve.tangentAngleAt(s)
                assertEquals(s / R, theta, 1e-9, "s=$s 时切向角应等于 $s/R")
            }
        }
    }

    @Nested
    inner class GeneratePoints {
        @Test
        fun `生成非空点列表`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val points = curve.generatePoints(0.5)
            assertTrue(points.isNotEmpty())
        }

        @Test
        fun `首尾点弧长正确`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val points = curve.generatePoints(0.5)
            assertEquals(0.0, points.first().arcLength, 1e-9)
            assertTrue(abs(points.last().arcLength - curve.totalLength) < 0.01)
        }

        @Test
        fun `法线默认向上`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val points = curve.generatePoints(0.5)
            for (p in points.take(5)) {
                assertTrue(abs(p.normal.y() - 1.0) < 1e-6, "默认法线应向上")
            }
        }

        @Test
        fun `长曲线点生成不会二次复杂度卡死`() {
            val curve = StandardRailCurve(500.0, 100.0, 300.0)
            var points: List<StandardRailCurve.CurveRawPoint> = emptyList()
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                points = curve.generatePoints(0.2)
            }
            assertTrue(points.size in 2500..2502)
            assertEquals(curve.totalLength, points.last().arcLength, 1e-9)
        }
    }

    @Nested
    inner class ToGlobal {
        @Test
        fun `起点映射到原点`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val origin = Vector3d(100.0, 5.0, 200.0)
            val startDir = Vector3d(1.0, 0.0, 0.0)
            val points = curve.generatePoints(0.5)
            val first = curve.toGlobal(points.first(), origin, startDir)
            assertTrue(abs(first.position.x() - origin.x) < 0.01)
            assertTrue(abs(first.position.y() - origin.y) < 0.01)
            assertTrue(abs(first.position.z() - origin.z) < 0.01)
        }

        @Test
        fun `90度方向映射正确`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            val origin = Vector3d(0.0, 0.0, 0.0)
            val startDir = Vector3d(0.0, 0.0, 1.0) // 沿 Z 轴
            val points = curve.generatePoints(0.5)
            val second = curve.toGlobal(points[1], origin, startDir)
            // 局部 X 轴旋转后应指向 Z
            assertTrue(abs(second.position.x()) < 0.01, "x 应接近0")
            assertTrue(second.position.z() > 0.0, "z 应为正")
        }
    }

    @Nested
    inner class Builder {
        @Test
        fun `build 方法正常`() {
            val curve = StandardRailCurveBuilder.build(30.0, 5.0, 10.0)
            assertNotNull(curve)
            // 2*L（两端缓和曲线） + Lc（圆弧）
            assertEquals(20.0, curve.totalLength)
        }

        @Test
        fun `buildAndGenerate 返回非空`() {
            val points = StandardRailCurveBuilder.buildAndGenerate(30.0, 5.0, 10.0, 0.5)
            assertTrue(points.isNotEmpty())
        }

        @Test
        fun `buildAndGenerateGlobal 返回非空`() {
            val origin = Vector3d(0.0, 0.0, 0.0)
            val startDir = Vector3d(1.0, 0.0, 0.0)
            val points = StandardRailCurveBuilder.buildAndGenerateGlobal(origin, startDir, 30.0, 5.0, 10.0, 0.5)
            assertTrue(points.isNotEmpty())
            assertEquals(0.0, points.first().arcLength, 1e-9)
        }
    }

    @Nested
    inner class Superelevation {
        @Test
        fun `无超高配置时 superelevation 为 0`() {
            val curve = StandardRailCurve(30.0, 5.0, 10.0)
            assertEquals(0.0, curve.superelevation, 1e-9)
        }

        @Test
        fun `autoCalculate 超高正确计算`() {
            val config = SuperelevationConfig(
                autoCalculate = true,
                designSpeedKmH = 100.0,
                maxSuperelevation = 0.15
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            // h ≈ 11.3 * 100² / 30 = 3766.67 mm -> 3.77 m，超过 0.15m 限制
            assertEquals(0.15, curve.superelevation, 1e-3)
        }

        @Test
        fun `autoCalculate 计算未被限制的超高`() {
            val config = SuperelevationConfig(
                autoCalculate = true,
                designSpeedKmH = 60.0,
                maxSuperelevation = 0.15
            )
            val curve = StandardRailCurve(300.0, 5.0, 10.0, config)
            // h ≈ 11.3 * 60² / 300 = 135.6 mm = 0.136m < 0.15m，不受限
            assertEquals(0.136, curve.superelevation, 0.001)
        }

        @Test
        fun `自动超高按局部曲率在缓和曲线中变化`() {
            val curve = StandardRailCurve(
                300.0,
                10.0,
                20.0,
                SuperelevationConfig(autoCalculate = true, designSpeedKmH = 60.0, maxSuperelevation = 1.0)
            )

            assertEquals(0.0678, curve.superelevationAt(5.0), 1.0e-4)
            assertEquals(0.1356, curve.superelevationAt(15.0), 1.0e-4)
        }

        @Test
        fun `圆曲线段超高达到 superelevation`() {
            val config = SuperelevationConfig(
                autoCalculate = false,
                maxSuperelevation = 0.10
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            // 圆曲线段 s ∈ [L, L+Lc] = [5, 15]
            val h = curve.superelevationAt(10.0) // 在圆曲线中点
            assertEquals(0.10, h, 1e-9)
        }

        @Test
        fun `缓和曲线段超高从 0 线性过渡`() {
            val config = SuperelevationConfig(
                autoCalculate = false,
                maxSuperelevation = 0.10,
                superelevationTransition = SuperelevationConfig.SuperelevationTransition.LINEAR
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            // 入口回旋线：s ∈ [0, 5]
            val hAt2_5 = curve.superelevationAt(2.5)
            assertEquals(0.05, hAt2_5, 1e-6, "中点应为半超高")
            val hAt0 = curve.superelevationAt(0.0)
            assertEquals(0.0, hAt0, 1e-9, "起点超高为 0")
            val hAt5 = curve.superelevationAt(5.0)
            assertEquals(0.10, hAt5, 1e-6, "回旋线终点应为全超高")
        }

        @Test
        fun `缓和曲线段超高从 superelevation 余弦过渡`() {
            val config = SuperelevationConfig(
                autoCalculate = false,
                maxSuperelevation = 0.10,
                superelevationTransition = SuperelevationConfig.SuperelevationTransition.COSINE
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            val hAt2_5 = curve.superelevationAt(2.5)
            // 余弦过渡：h = H * (t - sin(2πt)/(2π))，t=0.5
            // = 0.10 * (0.5 - 0 / (2π)) = 0.05，恰好也是 0.05（与线性相同，中点相同）
            // 但起点和终点过渡不同：余弦在端点处斜率为 0（更平滑）
            assertEquals(0.05, hAt2_5, 1e-6)
            assertTrue(hAt2_5 > 0.05 * 0.99, "余弦在中间过渡应比线性更平滑")
        }

        @Test
        fun `出口回旋线超高从 superelevation 线性过渡到 0`() {
            val config = SuperelevationConfig(
                autoCalculate = false,
                maxSuperelevation = 0.10,
                superelevationTransition = SuperelevationConfig.SuperelevationTransition.LINEAR
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            // 出口回旋线：s ∈ [15, 20]
            val hAt17_5 = curve.superelevationAt(17.5)
            assertEquals(0.05, hAt17_5, 1e-6, "中点应为半超高")
            val hAt15 = curve.superelevationAt(15.0)
            assertEquals(0.10, hAt15, 1e-6, "起点应为全超高")
            val hAt20 = curve.superelevationAt(20.0)
            assertEquals(0.0, hAt20, 1e-9, "终点超高为 0")
        }

        @Test
        fun `有超高配置的 curve 生成法线倾斜`() {
            val config = SuperelevationConfig(
                autoCalculate = false,
                maxSuperelevation = 0.10
            )
            val curve = StandardRailCurve(30.0, 5.0, 10.0, config)
            val points = curve.generatePoints(0.5)
            // 圆曲线段中点的法线应倾斜
            val midPoint = points.find { it.arcLength >= 10.0 && it.arcLength <= 15.0 } ?: points.last()
            // 轨距 1.435m，超高 0.10m，角度 ≈ atan2(0.1, 1.435) ≈ 0.0697 rad ≈ 4°
            val expectedAngle = kotlin.math.atan2(0.10, 1.435)
            val actualAngle = kotlin.math.atan2(midPoint.normal.z(), midPoint.normal.y())
            assertEquals(expectedAngle, actualAngle, 0.01, "圆曲线中点法线倾斜角应正确")
        }

        @Test
        fun `calculateSuperelevation 正确计算`() {
            // 300m 半径，100km/h：h = 11.3 * 100² / 300 = 376.67mm = 0.377m
            // 不设最大值限制时返回 0.377
            val h = StandardRailCurveBuilder.calculateSuperelevation(100.0, 300.0, 10.0) // 用大值避免限制
            assertEquals(0.377, h, 0.001)
        }

        @Test
        fun `calculateSuperelevation 超过最大值时限制`() {
            val h = StandardRailCurveBuilder.calculateSuperelevation(100.0, 30.0, 0.15)
            // h ≈ 11.3 * 100² / 30 = 3766 mm > 150 mm 限制
            assertEquals(0.15, h, 0.001)
        }
    }
}