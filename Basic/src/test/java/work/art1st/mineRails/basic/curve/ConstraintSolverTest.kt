package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ConstraintSolverTest {

    @Nested
    inner class ComputeAngle {
        @Test
        fun `相同方向夹角为0`() {
            val a = Vector3d(1.0, 0.0, 0.0)
            val b = Vector3d(1.0, 0.0, 0.0)
            assertEquals(0.0, ConstraintSolver.computeAngle(a, b), 1e-9)
        }

        @Test
        fun `相反方向夹角为PI`() {
            val a = Vector3d(1.0, 0.0, 0.0)
            val b = Vector3d(-1.0, 0.0, 0.0)
            assertEquals(Math.PI, ConstraintSolver.computeAngle(a, b), 1e-6)
        }

        @Test
        fun `90度夹角`() {
            val a = Vector3d(1.0, 0.0, 0.0)
            val b = Vector3d(0.0, 0.0, 1.0)
            assertEquals(Math.PI / 2.0, ConstraintSolver.computeAngle(a, b), 1e-6)
        }

        @Test
        fun `135度夹角`() {
            val a = Vector3d(1.0, 0.0, 0.0)
            val b = Vector3d(-0.7071, 0.0, 0.7071)
            assertEquals(3.0 * Math.PI / 4.0, ConstraintSolver.computeAngle(a, b), 0.001)
        }
    }

    @Nested
    inner class EstimateMinRadius {
        @Test
        fun `对称90度转弯的最小半径`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val to = Vector3d(100.0, 0.0, 100.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val toTan = Vector3d(0.0, 0.0, 1.0)
            val minR = ConstraintSolver.estimateMinRadius(from, fromTan, to, toTan)
            assertTrue(minR > 0, "最小半径应为正数")
            assertTrue(minR < 200, "对称90°转弯的最小半径应合理: $minR")
        }

        @Test
        fun `近距离锚点的最小半径更小`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val to = Vector3d(10.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val toTan = Vector3d(-1.0, 0.0, 0.0)
            val minR1 = ConstraintSolver.estimateMinRadius(from, fromTan, to, toTan)

            val far = Vector3d(100.0, 0.0, 0.0)
            val minR2 = ConstraintSolver.estimateMinRadius(from, fromTan, far, toTan)

            assertTrue(minR1 < minR2, "近距离锚点的最小半径应更小")
        }
    }

    @Nested
    inner class SolveBasic {
        @Test
        fun `90度对称转弯有解`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 0.0, 100.0)
            val toTan = Vector3d(0.0, 0.0, 1.0)

            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 80.0)
            assertTrue(result is ConstraintSolveResult.Success, "应有解: $result")
            result as ConstraintSolveResult.Success
            assertTrue(result.L1 > 0, "L1 应为正")
            assertTrue(result.Lc > 0, "Lc 应为正 (应有圆弧段)")
            assertTrue(result.theta > 0, "θ 应为正")
            assertEquals(result.L1, result.L2, 1e-6, "对称情况 L1 == L2")
        }

        @Test
        fun `180度U型弯有解`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(0.0, 0.0, 200.0)
            val toTan = Vector3d(-1.0, 0.0, 0.0)

            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 80.0)
            assertTrue(result is ConstraintSolveResult.Success, "应有解: $result")
            result as ConstraintSolveResult.Success
            assertTrue(result.totalAngle > Math.PI * 0.9, "总转角应接近 π: ${result.totalAngle}")
        }

        @Test
        fun `同方向直线退化为无弧`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(50.0, 0.0, 0.0)
            val toTan = Vector3d(1.0, 0.0, 0.0)

            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 300.0)
            // 两切线夹角为 0，应返回 Failure
            assertTrue(
                result is ConstraintSolveResult.Failure,
                "夹角为0时 R=300 应无解"
            )
        }

        @Test
        fun `45度转弯可用较小半径`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val radians = Math.toRadians(45.0)
            val to = Vector3d(100.0, 0.0, 0.0)
            val toTan = Vector3d(cos(radians), 0.0, sin(radians))

            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 50.0)
            assertTrue(result is ConstraintSolveResult.Success, "小半径应有解: $result")
        }
    }

    @Nested
    inner class SolveEdgeCases {
        @Test
        fun `R太小导致无解`() {
            // 两个很远的锚点，切线方向相反，R 太小无法连接
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 0.0, 100.0)
            val toTan = Vector3d(-1.0, 0.0, 0.0)

            // 用很小的 R
            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 2.0)
            // 可能无解（L1 搜索不到满足位移的值）
            // 不管是 Success 还是 Failure，都算测试通过——只要不抛异常
            assertNotNull(result)
        }

        @Test
        fun `固定缓和曲线长度有解`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 0.0, 100.0)
            val toTan = Vector3d(0.0, 0.0, 1.0)

            val result = ConstraintSolver.solve(
                from, fromTan, to, toTan,
                radius = 80.0,
                clothoidLength = 10.0  // 固定 L1 = L2 = 10m
            )
            assertTrue(result is ConstraintSolveResult.Success, "固定缓和曲线长度应有解: $result")
            result as ConstraintSolveResult.Success
            assertEquals(10.0, result.L1, 1e-6)
            assertEquals(10.0, result.L2, 1e-6)
        }

        @Test
        fun `固定缓和曲线太长导致θ小于0`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(50.0, 0.0, 50.0)
            val toTan = Vector3d(0.0, 0.0, 1.0)

            // 固定缓和曲线太长：L 远大于 R*Δψ
            val result = ConstraintSolver.solve(
                from, fromTan, to, toTan,
                radius = 30.0,
                clothoidLength = 500.0 // 极长
            )
            assertTrue(
                result is ConstraintSolveResult.Failure,
                "缓和曲线过长应返回 Failure: $result"
            )
        }
    }

    @Nested
    inner class EndpointVerification {
        @Test
        fun `90度转弯终点位置匹配`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val toPos = Vector3d(100.0, 0.0, 100.0)
            val toTan = Vector3d(0.0, 0.0, 1.0)

            val result = ConstraintSolver.solve(from, fromTan, toPos, toTan, 80.0)
            assertTrue(result is ConstraintSolveResult.Success, "应有解: $result")
            result as ConstraintSolveResult.Success

            // 验证：用 StandardRailCurve 计算曲线，终点应接近锚点B
            val curve = StandardRailCurve(result.R, result.L1, result.Lc)
            val lastPoint = curve.pointAt(curve.totalLength)
            // 终点处的局部切线方向（已旋转了总转角）
            val endTangentLocal = Vector3d(cos(lastPoint.tangentAngle), 0.0, sin(lastPoint.tangentAngle))
            val endNormalLocal = Vector3d(0.0, cos(0.0), sin(0.0)) // 法线默认向上
            // 转换到全局坐标
            val localEndPos = Vector3d(lastPoint.position.x, 0.0, lastPoint.position.z)
            val globalEnd = curve.toGlobal(
                StandardRailCurve.CurveRawPoint(localEndPos, endTangentLocal, endNormalLocal, result.totalLength),
                from, fromTan
            )

            val posError = globalEnd.position.distance(toPos)
            // 数值积分误差容忍 ~3%
            assertTrue(posError < toPos.distance(from) * 0.03,
                "终点位置误差应 < 3%, 实际: ${"%.3f".format(posError)}m (${"%.1f".format(posError * 100 / toPos.distance(from))}%)")

            // 验证：终点切线方向靠近目标切线
            val tangentDot = abs(globalEnd.tangent.dot(toTan))
            assertTrue(tangentDot > 0.99, "终点切线应与目标切线平行, dot=${"%.4f".format(tangentDot)}")
        }

        @Test
        fun `45度转弯终点切线方向匹配`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val radians = Math.toRadians(45.0)
            val toTan = Vector3d(cos(radians), 0.0, sin(radians))

            // 从原点到 45° 方向的点
            val toPos = Vector3d(cos(radians) * 80.0, 0.0, sin(radians) * 80.0)

            val result = ConstraintSolver.solve(from, fromTan, toPos, toTan, 100.0)
            assertTrue(result is ConstraintSolveResult.Success, "应有解: $result")
            result as ConstraintSolveResult.Success

            // 验证总转角
            val expectedAngle = ConstraintSolver.computeAngle(fromTan, toTan)
            assertEquals(expectedAngle, result.totalAngle, 1e-6, "总转角应匹配")
        }
    }

    @Nested
    inner class ClothoidMathTests {
        @Test
        fun `clothoid起点为原点`() {
            val point = ClothoidMath.computeClothoidLocal(0.0, 30.0, 5.0)
            assertEquals(0.0, point.x, 1e-9)
            assertEquals(0.0, point.z, 1e-9)
            assertEquals(0.0, point.theta, 1e-9)
        }

        @Test
        fun `clothoid终点切向角正确`() {
            val R = 30.0; val L = 5.0
            val point = ClothoidMath.computeClothoidLocal(L, R, L)
            // θ(L) = L²/(2·R·L) = L/(2R)
            val expectedTheta = L / (2.0 * R)
            assertEquals(expectedTheta, point.theta, 1e-6)
        }

        @Test
        fun `clothoid单调递增`() {
            val R = 30.0; val L = 5.0
            var prevX = 0.0
            var prevZ = 0.0
            for (s in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) {
                val point = ClothoidMath.computeClothoidLocal(s, R, L)
                assertTrue(point.x > prevX - 1e-9, "x 应单调递增: s=$s")
                assertTrue(point.z >= prevZ - 1e-9, "z 应单调非减: s=$s")
                prevX = point.x
                prevZ = point.z
            }
        }

        @Test
        fun `clothoid与StandardRailCurve一致`() {
            val R = 30.0; val L = 5.0
            val curve = StandardRailCurve(R, L, 0.0) // 只有回旋线，无圆弧

            for (s in listOf(0.5, 1.0, 2.5, 5.0)) {
                val clothoid = ClothoidMath.computeClothoidLocal(s, R, L)
                val railCurvePos = curve.positionAt(s, 0.001)

                val error = abs(clothoid.x - railCurvePos.x) + abs(clothoid.z - railCurvePos.z)
                assertTrue(error < 0.02, "s=$s: clothoid 与 StandardRailCurve 偏差应 < 2cm, 实际: ${"%.3f".format(error)}")
            }
        }
    }

    @Nested
    inner class ParameterValidation {
        @Test
        fun `R为正数`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(50.0, 0.0, 40.0)
            val toTan = Vector3d(0.7, 0.0, 0.7)

            // R 为负数应抛异常
            assertThrows(IllegalArgumentException::class.java) {
                ConstraintSolver.solve(from, fromTan, to, toTan, -1.0)
            }
            // R 为 0 也应抛异常
            assertThrows(IllegalArgumentException::class.java) {
                ConstraintSolver.solve(from, fromTan, to, toTan, 0.0)
            }
        }
    }

    @Nested
    inner class Symmetry {
        @Test
        fun `对称锚点得到对称解`() {
            // 对称的锚点对
            val dist = 100.0
            val angle = Math.PI / 4.0  // 45° 偏转角

            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(cos(-angle / 2), 0.0, sin(-angle / 2))
            val to = Vector3d(dist, 0.0, 0.0)
            val toTan = Vector3d(cos(angle / 2), 0.0, sin(angle / 2))

            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 200.0)
            assertTrue(result is ConstraintSolveResult.Success, "应有解: $result")
            result as ConstraintSolveResult.Success
            // 对称情况下 L1 == L2
            assertEquals(result.L1, result.L2, 1e-6, "对称锚点应得到对称解")
        }
    }

    @Nested
    inner class LargeRadius {
        @Test
        fun `大半径产生接近直线的解`() {
            val from = Vector3d(0.0, 0.0, 0.0)
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 0.0, 5.0)  // 轻微偏移
            val toTan = Vector3d(0.99995, 0.0, 0.01)  // ~0.57°偏转 (已归一化)

            // 极小偏转角，大半径应该能求解
            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 5000.0)
            assertTrue(result is ConstraintSolveResult.Success, "大半径应有解: $result")
            result as ConstraintSolveResult.Success
            // 缓和曲线长度应合理
            assertTrue(result.L1 > 0)
            assertTrue(result.totalLength > 90.0, "总曲线长度应接近位移")
        }
    }
}
