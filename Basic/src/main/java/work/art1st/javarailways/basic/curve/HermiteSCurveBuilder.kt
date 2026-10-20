package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import kotlin.math.abs
import kotlin.math.sqrt

/** Quintic Hermite sampling, exact-radius fitting and the associated signed cant profile. */
internal object HermiteSCurveBuilder {
    fun hermiteHandleLength(
        chord: Vector3dc,
        tangent2d: Vector3dc,
        horizontalDistance: Double,
        geometry: RailGeometryParameters
    ): Double {
        val projection = abs(chord.x() * tangent2d.x() + chord.z() * tangent2d.z())
        // Quintic zero-curvature endpoints stay flatter than cubic Hermite endpoints. A 3/4
        // derivative handle retains comparable automatic curvature and leaves fitting headroom.
        return (projection * 0.75)
            .coerceAtLeast(horizontalDistance * geometry.sCurveMinimumHandleFactor)
            .coerceAtMost(horizontalDistance * geometry.sCurveMaximumHandleFactor)
    }

    fun createHermiteSCurve(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent: Vector3dc,
        toTangent: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        fromRollDegrees: Double,
        toRollDegrees: Double,
        horizontalDistance: Double,
        superelevationConfig: SuperelevationConfig?,
        geometry: RailGeometryParameters
    ): PrimeCurve {
        val sampleCount = maxOf(
            geometry.sCurveMinimumSamples,
            kotlin.math.ceil(horizontalDistance / geometry.sCurveTargetStepMeters).toInt()
        )
        val horizontalSamples = (0..sampleCount).map { index ->
            val u = index.toDouble() / sampleCount
            val p = hermitePosition2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            val d = hermiteDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            p to d
        }
        val cumulative = MutableList(horizontalSamples.size) { 0.0 }
        for (i in 1 until horizontalSamples.size) {
            cumulative[i] = cumulative[i - 1] + horizontalSamples[i - 1].first.distance(horizontalSamples[i].first)
        }
        val totalLength = cumulative.last().coerceAtLeast(1e-9)
        val gA = CurveFrames.horizontalGrade(fromTangent)
        val gB = CurveFrames.horizontalGrade(toTangent)
        val dy = to.y() - from.y()
        val u = dy - gA * totalLength
        val v = gB - gA
        val c = (3.0 * u - v * totalLength) / (totalLength * totalLength)
        val d = (v * totalLength - 2.0 * u) / (totalLength * totalLength * totalLength)

        fun heightAt(arc: Double): Double = from.y() + gA * arc + c * arc * arc + d * arc * arc * arc
        fun gradeAt(arc: Double): Double = gA + 2.0 * c * arc + 3.0 * d * arc * arc
        val signedCurvatures = horizontalSamples.indices.map { index ->
            val parameter = index.toDouble() / sampleCount
            hermiteSignedCurvature(
                from,
                to,
                fromTangent2d,
                toTangent2d,
                handle0,
                handle1,
                parameter
            )
        }
        val peakCurvature = signedCurvatures.maxOf { abs(it) }
        val peakSuperelevation = if (superelevationConfig != null && peakCurvature > 1.0e-12) {
            superelevationConfig.calculateSuperelevation(1.0 / peakCurvature)
        } else {
            0.0
        }

        // The quintic Hermite plan has zero curvature at both anchors. Target cant follows signed
        // curvature, then each lobe is independently slew-limited from its zero-curvature bounds.
        // Unlike global scaling, this uses all cant-gradient headroom without exceeding local balance cant.
        val targetCantMeters = signedCurvatures.map { curvature ->
            if (peakSuperelevation <= 0.0 || peakCurvature <= 1.0e-12) 0.0
            else peakSuperelevation * curvature / peakCurvature
        }
        val cantMeters = limitSignedProfileGradient(
            targetCantMeters,
            cumulative,
            superelevationConfig?.maxCantGradient ?: 0.0
        )

        fun rollAt(index: Int, arc: Double): Double {
            val t = (arc / totalLength).coerceIn(0.0, 1.0)
            val anchorRoll = fromRollDegrees + (toRollDegrees - fromRollDegrees) * t
            val cantDegrees = Math.toDegrees(
                kotlin.math.atan2(cantMeters[index], geometry.gaugeMeters)
            )
            return anchorRoll + cantDegrees
        }

        val points = horizontalSamples.mapIndexed { index, sample ->
            val arc = cumulative[index]
            val horizontalDerivative = sample.second
            val horizontalDirection = if (index == 0) {
                Vector3d(fromTangent2d)
            } else if (index == horizontalSamples.lastIndex) {
                Vector3d(toTangent2d)
            } else {
                Vector3d(horizontalDerivative.x(), 0.0, horizontalDerivative.z()).normalize()
            }
            val position = Vector3d(sample.first.x(), heightAt(arc), sample.first.z())
            if (index == horizontalSamples.lastIndex) position.set(to.x(), to.y(), to.z())
            if (index == 0) position.set(from.x(), from.y(), from.z())
            val tangent = Vector3d(horizontalDirection.x(), gradeAt(arc), horizontalDirection.z()).normalize()
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val normal = CurveFrames.applyRollRadians(baseNormal, tangent, Math.toRadians(rollAt(index, arc)))
            Curve.RawPoint(position, tangent, normal, arc)
        }
        return PrimeCurve.fromPoints(RailCurvePartitioner.withElevationArc(points))
    }

    private fun limitSignedProfileGradient(
        target: List<Double>,
        stations: List<Double>,
        maximumGradient: Double
    ): List<Double> {
        require(target.size == stations.size)
        if (target.isEmpty() || maximumGradient <= 0.0) return List(target.size) { 0.0 }
        val limited = MutableList(target.size) { 0.0 }

        fun profileSign(value: Double): Int = when {
            value > 1.0e-12 -> 1
            value < -1.0e-12 -> -1
            else -> 0
        }

        fun zeroStation(left: Int, right: Int): Double {
            val leftMagnitude = abs(target[left])
            val rightMagnitude = abs(target[right])
            val fraction = leftMagnitude / (leftMagnitude + rightMagnitude)
            return stations[left] + (stations[right] - stations[left]) * fraction
        }

        var start = target.indexOfFirst { profileSign(it) != 0 }
        while (start >= 0) {
            val lobeSign = profileSign(target[start])
            var end = start
            while (end + 1 < target.size && profileSign(target[end + 1]) == lobeSign) end++

            val leftBoundary = when {
                start == 0 -> stations.first()
                profileSign(target[start - 1]) == 0 -> stations[start - 1]
                else -> zeroStation(start - 1, start)
            }
            val rightBoundary = when {
                end == target.lastIndex -> stations.last()
                profileSign(target[end + 1]) == 0 -> stations[end + 1]
                else -> zeroStation(end, end + 1)
            }

            var previousMagnitude = 0.0
            var previousStation = leftBoundary
            for (index in start..end) {
                val targetMagnitude = abs(target[index])
                val gradientBound = previousMagnitude +
                    maximumGradient * (stations[index] - previousStation).coerceAtLeast(0.0)
                previousMagnitude = minOf(targetMagnitude, gradientBound)
                limited[index] = previousMagnitude * lobeSign
                previousStation = stations[index]
            }

            var nextMagnitude = 0.0
            var nextStation = rightBoundary
            for (index in end downTo start) {
                val gradientBound = nextMagnitude +
                    maximumGradient * (nextStation - stations[index]).coerceAtLeast(0.0)
                nextMagnitude = minOf(abs(limited[index]), gradientBound)
                limited[index] = nextMagnitude * lobeSign
                nextStation = stations[index]
            }

            start = (end + 1 until target.size).firstOrNull { profileSign(target[it]) != 0 } ?: -1
        }
        return limited
    }

    /** Quintic Hermite interpolation with zero second derivative at both endpoints. */
    private fun hermitePosition2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val u2 = u * u
        val u3 = u2 * u
        val u4 = u3 * u
        val u5 = u4 * u
        val h00 = 1.0 - 10.0 * u3 + 15.0 * u4 - 6.0 * u5
        val h10 = u - 6.0 * u3 + 8.0 * u4 - 3.0 * u5
        val h01 = 10.0 * u3 - 15.0 * u4 + 6.0 * u5
        val h11 = -4.0 * u3 + 7.0 * u4 - 3.0 * u5
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteDerivative2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val u2 = u * u
        val u3 = u2 * u
        val u4 = u3 * u
        val h00 = -30.0 * u2 + 60.0 * u3 - 30.0 * u4
        val h10 = 1.0 - 18.0 * u2 + 32.0 * u3 - 15.0 * u4
        val h01 = 30.0 * u2 - 60.0 * u3 + 30.0 * u4
        val h11 = -12.0 * u2 + 28.0 * u3 - 15.0 * u4
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteSecondDerivative2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val u2 = u * u
        val u3 = u2 * u
        val h00 = -60.0 * u + 180.0 * u2 - 120.0 * u3
        val h10 = -36.0 * u + 96.0 * u2 - 60.0 * u3
        val h01 = 60.0 * u - 180.0 * u2 + 120.0 * u3
        val h11 = -24.0 * u + 84.0 * u2 - 60.0 * u3
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteSignedCurvature(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Double {
        val d1 = hermiteDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
        val d2 = hermiteSecondDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
        val speedSq = d1.x() * d1.x() + d1.z() * d1.z()
        if (speedSq < 1.0e-12) return 0.0
        return (d1.x() * d2.z() - d1.z() * d2.x()) / (speedSq * sqrt(speedSq))
    }

    fun fitHermiteHandlesToExactRadius(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        baseHandle0: Double,
        baseHandle1: Double,
        targetRadius: Double,
        probeCount: Int
    ): Triple<Double, Double, Double> {
        fun radiusAt(scale: Double): Double = estimateHermiteMinRadius(
            from,
            to,
            fromTangent2d,
            toTangent2d,
            baseHandle0 * scale,
            baseHandle1 * scale,
            probeCount
        )

        val samples = (0..160).map { index ->
            val scale = 0.25 + (3.0 - 0.25) * index / 160.0
            scale to radiusAt(scale)
        }
        var best = samples.minBy { (_, radius) -> abs(radius - targetRadius) }
        samples.zipWithNext().forEach { (left, right) ->
            var lo = left.first
            var hi = right.first
            var loError = left.second - targetRadius
            val hiError = right.second - targetRadius
            if (!loError.isFinite() || !hiError.isFinite() || loError * hiError > 0.0) return@forEach
            repeat(48) {
                val mid = (lo + hi) / 2.0
                val radius = radiusAt(mid)
                if (abs(radius - targetRadius) < abs(best.second - targetRadius)) best = mid to radius
                val error = radius - targetRadius
                if (error == 0.0) return@repeat
                if (loError * error <= 0.0) {
                    hi = mid
                } else {
                    lo = mid
                    loError = error
                }
            }
        }
        return Triple(baseHandle0 * best.first, baseHandle1 * best.first, best.second)
    }

    fun estimateHermiteMinRadius(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        probeCount: Int
    ): Double {
        var maxCurvature = 0.0
        for (i in 0..probeCount) {
            val u = i.toDouble() / probeCount
            val curvature = abs(
                hermiteSignedCurvature(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            )
            maxCurvature = maxOf(maxCurvature, curvature)
        }
        return if (maxCurvature < 1e-12) Double.POSITIVE_INFINITY else 1.0 / maxCurvature
    }

}
