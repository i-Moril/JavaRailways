package work.art1st.javarailways.core.physics.rail.curve

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.util.ObjectPools
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import java.util.stream.Stream

open class PrimeCurveImpl : PrimeCurve {
    private var pointList: List<InternalCurvePoint> = emptyList()

    constructor(points: List<Curve.RawPoint>) {
        this.pointList = points.map { p ->
            InternalCurvePoint(
                position = Vector3d(p.position),
                tangent = Vector3d(p.tangent),
                normal = Vector3d(p.normal),
                arcLength = p.arcLength,
                segmentType = p.segmentType
            )
        }
        require(pointList.size >= 2) { "A curve requires at least two raw points" }
        require(pointList.first().arcLength == 0.0) { "The first curve arc length must be zero" }
        pointList.forEachIndexed { index, point ->
            require(point.arcLength.isFinite() &&
                (index == 0 || point.arcLength - pointList[index - 1].arcLength >= MIN_ARC_SPACING)) {
                "Curve point $index arc length must be finite and separated by at least $MIN_ARC_SPACING metres"
            }
            requireFiniteComponents(point.position, index, "position")
            requireFiniteComponents(point.tangent, index, "tangent")
            requireFiniteComponents(point.normal, index, "normal")
            val tangentLengthSquared = point.tangent.lengthSquared()
            val normalLengthSquared = point.normal.lengthSquared()
            require(tangentLengthSquared.isFinite() && tangentLengthSquared > 0.0) {
                "Curve point $index tangent must be normalizable"
            }
            require(normalLengthSquared.isFinite() && normalLengthSquared > 0.0) {
                "Curve point $index normal must be normalizable"
            }
            val crossLengthSquared = Vector3d(point.tangent).normalize()
                .cross(Vector3d(point.normal).normalize()).lengthSquared()
            require(crossLengthSquared.isFinite() && crossLengthSquared > 0.0) {
                "Curve point $index tangent and normal must not be parallel"
            }
        }
        validateInterpolatedFrames()
    }

    /** Prove nonzero T(t) x N(t) over each whole interval using its quadratic Bernstein hull. */
    private fun validateInterpolatedFrames() {
        for (index in 0 until pointList.lastIndex) {
            val lo = pointList[index]
            val hi = pointList[index + 1]
            requireFiniteComponents(Vector3d(hi.position).sub(lo.position), index, "position delta")
            if (lo.segmentType == Curve.SegmentType.LINE_START &&
                hi.segmentType == Curve.SegmentType.LINE_END) continue
            val tangentScale = maxOf(lo.tangent.length(), hi.tangent.length())
            val normalScale = maxOf(lo.normal.length(), hi.normal.length())
            val t0 = Vector3d(lo.tangent).div(tangentScale)
            val t1 = Vector3d(hi.tangent).div(tangentScale)
            val n0 = Vector3d(lo.normal).div(normalScale)
            val n1 = Vector3d(hi.normal).div(normalScale)
            val start = Vector3d(t0).cross(n0)
            val middle = Vector3d(t0).cross(n1).add(Vector3d(t1).cross(n0)).mul(0.5)
            val end = Vector3d(t1).cross(n1)
            require(provesNonzeroFrame(start, middle, end, 0, intArrayOf(256))) {
                "Curve interval $index has a degenerate or numerically unsafe interpolated frame"
            }
        }
    }

    private fun provesNonzeroFrame(a: Vector3d, b: Vector3d, c: Vector3d, depth: Int, budget: IntArray): Boolean {
        if (--budget[0] < 0) return false
        // A Bernstein polynomial lies in the convex hull of its control coefficients. A strictly
        // signed coordinate therefore excludes the zero vector everywhere, not only at samples.
        for (axis in 0..2) {
            val low = minOf(a.get(axis), b.get(axis), c.get(axis))
            val high = maxOf(a.get(axis), b.get(axis), c.get(axis))
            if (low > FRAME_MARGIN || high < -FRAME_MARGIN) return true
        }
        // Fail closed when roundoff/near-degeneracy prevents a proof. Subdivision uses only bounded
        // additions and halves; the margin exceeds accumulated double roundoff over this depth.
        if (depth == 32) return false
        val ab = Vector3d(a).add(b).mul(0.5)
        val bc = Vector3d(b).add(c).mul(0.5)
        val center = Vector3d(ab).add(bc).mul(0.5)
        return provesNonzeroFrame(a, ab, center, depth + 1, budget) &&
            provesNonzeroFrame(center, bc, c, depth + 1, budget)
    }

    private constructor()

    companion object {
        private fun requireFiniteComponents(vector: Vector3dc, index: Int, field: String) {
            require(vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()) {
                "Curve point $index $field must have finite components"
            }
        }

        @JvmStatic
        internal fun fromRawPoints(points: List<Curve.RawPoint>): PrimeCurveImpl {
            return PrimeCurveImpl(points)
        }

        /** stream() 中直线段虚拟采样步长 */
        private const val STREAM_LINE_STEP = 1.0

        private const val MIN_ARC_SPACING = 1e-12
        private const val FRAME_MARGIN = 1e-12
    }

    @Serializable
    private class InternalCurvePoint(
        @Serializable(with = Vector3dSerializer::class)
        val position: Vector3d,
        @Serializable(with = Vector3dSerializer::class)
        val tangent: Vector3d,
        @Serializable(with = Vector3dSerializer::class)
        val normal: Vector3d,
        val arcLength: Double,
        val segmentType: Curve.SegmentType = Curve.SegmentType.DISCRETE
    )

    override val totalArcLength: Double
        get() = pointList.last().arcLength

    @Transient
    override val reversed: PrimeCurve = ReversedCurve(this)

    @Heavy
    override fun getNearestPoint(position: Vector3dc): Curve.Point {
        val nearestArc = ObjectPools.withTwoVector3d { segment, relative ->
            var bestArc = pointList.first().arcLength
            var bestDistanceSquared = Double.POSITIVE_INFINITY
            // Both LINE and DISCRETE positions interpolate along these stored segments.
            for (index in 0 until pointList.lastIndex) {
                val lo = pointList[index]
                val hi = pointList[index + 1]
                hi.position.sub(lo.position, segment)
                position.sub(lo.position, relative)
                val lengthSquared = segment.lengthSquared()
                val t = if (lengthSquared == 0.0) 0.0 else
                    (relative.dot(segment) / lengthSquared).coerceIn(0.0, 1.0)
                val distanceSquared = relative.fma(-t, segment).lengthSquared()
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared
                    bestArc = when (t) {
                        0.0 -> lo.arcLength
                        1.0 -> hi.arcLength
                        else -> lo.arcLength + (hi.arcLength - lo.arcLength) * t
                    }
                }
            }
            bestArc
        }
        return getPointAt(nearestArc)
    }

    override fun getPointAt(arcLength: Double): Curve.Point {
        require(arcLength in 0.0..totalArcLength) {
            "Invalid arcLength $arcLength. Range: [0, $totalArcLength]"
        }
        // 先处理边界
        if (arcLength == totalArcLength) {
            val p = pointList.last()
            return Curve.Point(p.position, p.tangent, p.normal, this, p.arcLength)
        }
        // 定位弧长所在区间的左端点，不能用“最近点”搜索。
        // 两点直线 [0, L] 上，如果 arcLength > L/2 时选到终点作为 lo，
        // 后半段会退化成 range=0，表现为直线只有一半长度。
        val idx = findSegmentStartIndex(arcLength)
        val lo = pointList[idx]
        val hi = pointList[minOf(idx + 1, pointList.lastIndex)]

        // 直线段：精确线性插值（位置线性，切线/法线恒定）
        if (lo.segmentType == Curve.SegmentType.LINE_START &&
            hi.segmentType == Curve.SegmentType.LINE_END) {
            val range = hi.arcLength - lo.arcLength
            val t = (arcLength - lo.arcLength) / range
            // P3: 用池化临时向量，减少 GC 压力
            return ObjectPools.withTwoVector3d { dir, result ->
                Vector3d(hi.position).sub(lo.position, dir).mul(t)
                Vector3d(lo.position).add(dir, result)
                Curve.Point(Vector3d(result), lo.tangent, lo.normal, this, arcLength)
            }
        }

        // 离散/曲线段：相邻点之间线性插值
        val range = hi.arcLength - lo.arcLength
        val t = (arcLength - lo.arcLength) / range
        ObjectPools.withTwoVector3d { dir, tmpRes ->
            // position
            Vector3d(hi.position).sub(lo.position, dir).mul(t)
            Vector3d(lo.position).add(dir, tmpRes)
            val pos = Vector3d(tmpRes)
            // tangent
            val tangentScale = maxOf(lo.tangent.length(), hi.tangent.length())
            Vector3d(hi.tangent).div(tangentScale).sub(Vector3d(lo.tangent).div(tangentScale), dir).mul(t)
            Vector3d(lo.tangent).div(tangentScale).add(dir, tmpRes).normalize()
            val tan = Vector3d(tmpRes)
            // normal — 线性插值后重新投影到切线垂直面 (Gram-Schmidt)
            val normalScale = maxOf(lo.normal.length(), hi.normal.length())
            Vector3d(hi.normal).div(normalScale).sub(Vector3d(lo.normal).div(normalScale), dir).mul(t)
            Vector3d(lo.normal).div(normalScale).add(dir, tmpRes).normalize()
            // 将 normal 投影到 tangent 的垂直面上，确保正交
            val dt = tmpRes.dot(tan)
            tmpRes.sub(Vector3d(tan).mul(dt)).normalize()
            val nor = Vector3d(tmpRes)
            return Curve.Point(pos, tan, nor, this, arcLength)
        }
    }

    override fun splitAt(arcLength: Double): Pair<PrimeCurve, PrimeCurve> {
        require(arcLength > 0.0 && arcLength < totalArcLength) {
            "Split arc length must be inside (0, $totalArcLength): $arcLength"
        }

        val exactIndex = pointList.indexOfFirst { kotlin.math.abs(it.arcLength - arcLength) <= 1e-9 }
        val leftPoints: List<InternalCurvePoint>
        val rightPoints: List<InternalCurvePoint>
        if (exactIndex >= 0) {
            val source = pointList[exactIndex]
            val leftType = if (
                exactIndex > 0 &&
                pointList[exactIndex - 1].segmentType == Curve.SegmentType.LINE_START &&
                source.segmentType == Curve.SegmentType.LINE_END
            ) Curve.SegmentType.LINE_END else Curve.SegmentType.DISCRETE
            val rightType = if (
                exactIndex < pointList.lastIndex &&
                source.segmentType == Curve.SegmentType.LINE_START &&
                pointList[exactIndex + 1].segmentType == Curve.SegmentType.LINE_END
            ) Curve.SegmentType.LINE_START else Curve.SegmentType.DISCRETE
            leftPoints = pointList.take(exactIndex) + source.copyPoint(arcLength = arcLength, segmentType = leftType)
            rightPoints = listOf(source.copyPoint(arcLength = 0.0, segmentType = rightType)) +
                pointList.drop(exactIndex + 1).map { it.copyPoint(arcLength = it.arcLength - arcLength) }
        } else {
            val loIndex = findSegmentStartIndex(arcLength)
            val lo = pointList[loIndex]
            val hi = pointList[loIndex + 1]
            val insideLine = lo.segmentType == Curve.SegmentType.LINE_START &&
                hi.segmentType == Curve.SegmentType.LINE_END
            val splitPoint = getPointAt(arcLength)
            val leftBoundary = InternalCurvePoint(
                Vector3d(splitPoint.position),
                Vector3d(splitPoint.tangent),
                Vector3d(splitPoint.normal),
                arcLength,
                if (insideLine) Curve.SegmentType.LINE_END else Curve.SegmentType.DISCRETE
            )
            val rightBoundary = InternalCurvePoint(
                Vector3d(splitPoint.position),
                Vector3d(splitPoint.tangent),
                Vector3d(splitPoint.normal),
                0.0,
                if (insideLine) Curve.SegmentType.LINE_START else Curve.SegmentType.DISCRETE
            )
            leftPoints = pointList.take(loIndex + 1) + leftBoundary
            rightPoints = listOf(rightBoundary) +
                pointList.drop(loIndex + 1).map { it.copyPoint(arcLength = it.arcLength - arcLength) }
        }

        return PrimeCurveImpl(leftPoints.map { it.toRawPoint() }) to
            PrimeCurveImpl(rightPoints.map { it.toRawPoint() })
    }

    override fun concatenate(next: PrimeCurve): PrimeCurve {
        val nextCurve = next as? PrimeCurveImpl
            ?: throw IllegalArgumentException("Only canonical sampled PrimeCurve instances can be concatenated")
        val leftEnd = pointList.last()
        val rightStart = nextCurve.pointList.first()
        require(leftEnd.position.distance(rightStart.position) <= Curve.TOLERANCE) {
            "Curve endpoints do not coincide"
        }
        require(Vector3d(leftEnd.tangent).normalize().dot(Vector3d(rightStart.tangent).normalize()) >= 1.0 - 1e-6) {
            "Curve endpoint tangents do not match"
        }
        require(Vector3d(leftEnd.normal).normalize().dot(Vector3d(rightStart.normal).normalize()) >= 1.0 - 1e-6) {
            "Curve endpoint normals do not match"
        }

        val splitInsideLine = pointList.size >= 2 && nextCurve.pointList.size >= 2 &&
            pointList[pointList.lastIndex - 1].segmentType == Curve.SegmentType.LINE_START &&
            leftEnd.segmentType == Curve.SegmentType.LINE_END &&
            rightStart.segmentType == Curve.SegmentType.LINE_START &&
            nextCurve.pointList[1].segmentType == Curve.SegmentType.LINE_END
        val elideDiscreteJunction = !splitInsideLine && canElideDiscreteJunction(nextCurve)
        val joined = if (splitInsideLine || elideDiscreteJunction) {
            pointList.dropLast(1) + nextCurve.pointList.drop(1).map {
                it.copyPoint(arcLength = totalArcLength + it.arcLength)
            }
        } else {
            val junctionType = when {
                rightStart.segmentType == Curve.SegmentType.LINE_START -> Curve.SegmentType.LINE_START
                leftEnd.segmentType == Curve.SegmentType.LINE_END -> Curve.SegmentType.LINE_END
                else -> Curve.SegmentType.DISCRETE
            }
            pointList.dropLast(1) + leftEnd.copyPoint(segmentType = junctionType) +
                nextCurve.pointList.drop(1).map { it.copyPoint(arcLength = totalArcLength + it.arcLength) }
        }
        return PrimeCurveImpl(joined.map { it.toRawPoint() })
    }

    private fun canElideDiscreteJunction(next: PrimeCurveImpl): Boolean {
        if (pointList.size < 2 || next.pointList.size < 2) return false
        val previous = pointList[pointList.lastIndex - 1]
        val junction = pointList.last()
        val nextPoint = next.pointList[1]
        if (previous.segmentType != Curve.SegmentType.DISCRETE ||
            junction.segmentType != Curve.SegmentType.DISCRETE ||
            next.pointList.first().segmentType != Curve.SegmentType.DISCRETE ||
            nextPoint.segmentType != Curve.SegmentType.DISCRETE
        ) return false

        val span = nextPoint.arcLength + (junction.arcLength - previous.arcLength)
        if (span <= 1e-12) return false
        // Removing a valid junction may create an unsafe frame interval. Elision is optional;
        // retain the original two validated intervals when the proposed replacement is rejected.
        val probeCurve = try {
            PrimeCurveImpl(listOf(
                previous.copyPoint(arcLength = 0.0).toRawPoint(),
                nextPoint.copyPoint(arcLength = span).toRawPoint()
            ))
        } catch (_: IllegalArgumentException) {
            return false
        }
        val probe = probeCurve.getPointAt(junction.arcLength - previous.arcLength)
        return probe.position.distance(junction.position) <= 1e-9 &&
            probe.tangent.distance(junction.tangent) <= 1e-9 &&
            probe.normal.distance(junction.normal) <= 1e-9
    }

    private fun InternalCurvePoint.copyPoint(
        arcLength: Double = this.arcLength,
        segmentType: Curve.SegmentType = this.segmentType
    ): InternalCurvePoint = InternalCurvePoint(
        Vector3d(position),
        Vector3d(tangent),
        Vector3d(normal),
        arcLength,
        segmentType
    )

    private fun InternalCurvePoint.toRawPoint(): Curve.RawPoint = Curve.RawPoint(
        Vector3d(position),
        Vector3d(tangent),
        Vector3d(normal),
        arcLength,
        segmentType
    )

    private fun findSegmentStartIndex(arcLength: Double): Int {
        var lo = 0
        var hi = pointList.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pointList[mid].arcLength <= arcLength) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return lo.coerceAtMost(pointList.lastIndex - 1)
    }

    /** Exact endpoints of the implementation's piecewise-linear geometry, without stream virtual samples. */
    internal fun geometryBreakpoints(): List<Curve.Point> = pointList.map { point ->
        Curve.Point(point.position, point.tangent, point.normal, this, point.arcLength)
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        // 对 LINE 段按步长生成虚拟点；对 DISCRETE 段用存储点
        val builder = Stream.builder<Curve.Point>()
        for (i in 0 until pointList.lastIndex) {
            val lo = pointList[i]
            val hi = pointList[i + 1]
            if (lo.segmentType == Curve.SegmentType.LINE_START &&
                hi.segmentType == Curve.SegmentType.LINE_END) {
                val range = hi.arcLength - lo.arcLength
                val n = maxOf(1, (range / STREAM_LINE_STEP).toInt())
                for (j in 0 until n) {
                    val arc = (lo.arcLength + range * j.toDouble() / n).coerceIn(lo.arcLength, hi.arcLength)
                    builder.add(getPointAt(arc))
                }
            } else {
                builder.add(Curve.Point(lo.position, lo.tangent, lo.normal, this, lo.arcLength))
            }
        }
        // 最后一个点
        val last = pointList.last()
        builder.add(Curve.Point(last.position, last.tangent, last.normal, this, last.arcLength))
        return builder.build()
    }

    override fun save(): PrimeCurve.SavedPrimeCurve {
        return SavedPrimeCurveV1.build(this)
    }

    @ConsistentCopyVisibility
    @Serializable
    data class SavedPrimeCurveV1 private constructor(
        private val pointList: List<InternalCurvePoint>
    ) : PrimeCurve.SavedPrimeCurve {
        override fun load(dependency: Unit): PrimeCurve {
            return PrimeCurveImpl.fromRawPoints(pointList.map { p ->
                Curve.RawPoint(p.position, p.tangent, p.normal, p.arcLength, p.segmentType)
            })
        }

        companion object {
            fun build(curve: PrimeCurveImpl): SavedPrimeCurveV1 {
                return SavedPrimeCurveV1(curve.pointList)
            }
        }
    }
}

object Vector3dSerializer : KSerializer<Vector3d> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3d") {
        element<Double>("x")
        element<Double>("y")
        element<Double>("z")
    }

    override fun serialize(encoder: Encoder, value: Vector3d) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x)
            encodeDoubleElement(descriptor, 1, value.y)
            encodeDoubleElement(descriptor, 2, value.z)
        }
    }

    override fun deserialize(decoder: Decoder): Vector3d {
        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> x = decodeDoubleElement(descriptor, 0)
                    1 -> y = decodeDoubleElement(descriptor, 1)
                    2 -> z = decodeDoubleElement(descriptor, 2)
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return Vector3d(
            x ?: throw SerializationException("Missing x coordinate in Vector3d"),
            y ?: throw SerializationException("Missing y coordinate in Vector3d"),
            z ?: throw SerializationException("Missing z coordinate in Vector3d")
        )
    }
}
