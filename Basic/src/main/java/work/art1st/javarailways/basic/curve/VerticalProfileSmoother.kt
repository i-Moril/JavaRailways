package work.art1st.javarailways.basic.curve

import kotlin.math.abs

/** A cubic vertical-profile segment parameterized by horizontal station from 0 to [length]. */
data class VerticalProfileSegment(
    val length: Double,
    val startElevation: Double,
    val endElevation: Double,
    val startGrade: Double,
    val endGrade: Double
) {
    init {
        require(length.isFinite() && length > 0.0)
        require(listOf(startElevation, endElevation, startGrade, endGrade).all(Double::isFinite))
    }

    fun heightAt(localStation: Double): Double {
        val t = (localStation / length).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return (2.0 * t3 - 3.0 * t2 + 1.0) * startElevation +
            (t3 - 2.0 * t2 + t) * length * startGrade +
            (-2.0 * t3 + 3.0 * t2) * endElevation +
            (t3 - t2) * length * endGrade
    }

    fun gradeAt(localStation: Double): Double {
        val t = (localStation / length).coerceIn(0.0, 1.0)
        val dyDt = (6.0 * t * t - 6.0 * t) * startElevation +
            (3.0 * t * t - 4.0 * t + 1.0) * length * startGrade +
            (-6.0 * t * t + 6.0 * t) * endElevation +
            (3.0 * t * t - 2.0 * t) * length * endGrade
        return dyDt / length
    }

    fun curvatureAt(localStation: Double): Double {
        val t = (localStation / length).coerceIn(0.0, 1.0)
        val secondByT = (12.0 * t - 6.0) * startElevation +
            (6.0 * t - 4.0) * length * startGrade +
            (-12.0 * t + 6.0) * endElevation +
            (6.0 * t - 2.0) * length * endGrade
        return secondByT / (length * length)
    }
}

data class VerticalProfileSolution(
    val stations: List<Double>,
    val elevations: List<Double>,
    val grades: List<Double>,
    val segments: List<VerticalProfileSegment>
)

/**
 * Solves the editable grades of a profile passing through fixed anchor elevations.
 *
 * Free grades minimize the integral of squared vertical curvature. This is equivalent to a
 * clamped cubic spline and keeps curvature continuous at every free anchor. Explicit grades,
 * horizontal-segment endpoints, and detected crests/sags are hard first-derivative constraints.
 */
object VerticalProfileSmoother {
    private const val EPSILON = 1.0e-9

    fun solve(
        stations: List<Double>,
        elevations: List<Double>,
        startGrade: Double,
        endGrade: Double,
        horizontalSegments: Set<Int> = emptySet(),
        fixedGrades: Map<Int, Double> = emptyMap()
    ): VerticalProfileSolution {
        require(stations.size == elevations.size) { "Station and elevation counts differ" }
        require(stations.size >= 2) { "A vertical profile needs at least two anchors" }
        require(stations.all(Double::isFinite) && elevations.all(Double::isFinite))
        require(startGrade.isFinite() && endGrade.isFinite())
        stations.zipWithNext().forEach { (a, b) ->
            require(b > a) { "Vertical-profile stations must increase strictly" }
        }
        horizontalSegments.forEach { index ->
            require(index in 0 until stations.lastIndex) { "Invalid horizontal segment $index" }
            require(abs(elevations[index + 1] - elevations[index]) <= EPSILON) {
                "Horizontal segment $index has different endpoint elevations"
            }
        }
        fixedGrades.forEach { (index, grade) ->
            require(index in stations.indices && grade.isFinite())
        }

        val fixed = linkedMapOf<Int, Double>()
        fixed[0] = startGrade
        fixed[stations.lastIndex] = endGrade
        fixed.putAll(fixedGrades)

        // A selected local height extremum explicitly denotes a crest or sag.
        for (index in 1 until elevations.lastIndex) {
            val before = elevations[index] - elevations[index - 1]
            val after = elevations[index + 1] - elevations[index]
            if ((before > EPSILON && after < -EPSILON) || (before < -EPSILON && after > EPSILON)) {
                fixed[index] = 0.0
            }
        }
        horizontalSegments.forEach { index ->
            fixed[index] = 0.0
            fixed[index + 1] = 0.0
        }

        val freeIndices = stations.indices.filterNot(fixed::containsKey)
        val freePosition = freeIndices.withIndex().associate { (position, index) -> index to position }
        val matrix = Array(freeIndices.size) { DoubleArray(freeIndices.size) }
        val rhs = DoubleArray(freeIndices.size)

        freeIndices.forEachIndexed { row, index ->
            // Endpoints are always fixed, so every free node has intervals on both sides.
            val leftLength = stations[index] - stations[index - 1]
            val rightLength = stations[index + 1] - stations[index]
            val leftSecant = (elevations[index] - elevations[index - 1]) / leftLength
            val rightSecant = (elevations[index + 1] - elevations[index]) / rightLength

            matrix[row][row] = 2.0 / leftLength + 2.0 / rightLength
            rhs[row] = 3.0 * leftSecant / leftLength + 3.0 * rightSecant / rightLength
            addNeighborCoefficient(index - 1, 1.0 / leftLength, row, matrix, rhs, fixed, freePosition)
            addNeighborCoefficient(index + 1, 1.0 / rightLength, row, matrix, rhs, fixed, freePosition)
        }

        val solvedFree = solveLinearSystem(matrix, rhs)
        val grades = stations.indices.map { index ->
            fixed[index] ?: solvedFree[freePosition.getValue(index)]
        }
        val segments = (0 until stations.lastIndex).map { index ->
            VerticalProfileSegment(
                length = stations[index + 1] - stations[index],
                startElevation = elevations[index],
                endElevation = elevations[index + 1],
                startGrade = grades[index],
                endGrade = grades[index + 1]
            )
        }
        return VerticalProfileSolution(stations.toList(), elevations.toList(), grades, segments)
    }

    private fun addNeighborCoefficient(
        neighborIndex: Int,
        coefficient: Double,
        row: Int,
        matrix: Array<DoubleArray>,
        rhs: DoubleArray,
        fixed: Map<Int, Double>,
        freePosition: Map<Int, Int>
    ) {
        val fixedGrade = fixed[neighborIndex]
        if (fixedGrade != null) {
            rhs[row] -= coefficient * fixedGrade
        } else {
            matrix[row][freePosition.getValue(neighborIndex)] += coefficient
        }
    }

    private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        if (rhs.isEmpty()) return DoubleArray(0)
        val n = rhs.size
        val augmented = Array(n) { row -> DoubleArray(n + 1).also { values ->
            matrix[row].copyInto(values)
            values[n] = rhs[row]
        } }
        for (column in 0 until n) {
            val pivot = (column until n).maxBy { row -> abs(augmented[row][column]) }
            require(abs(augmented[pivot][column]) > EPSILON) { "Vertical-profile constraints are singular" }
            if (pivot != column) {
                val swap = augmented[column]
                augmented[column] = augmented[pivot]
                augmented[pivot] = swap
            }
            val divisor = augmented[column][column]
            for (j in column..n) augmented[column][j] /= divisor
            for (row in 0 until n) {
                if (row == column) continue
                val factor = augmented[row][column]
                if (abs(factor) <= EPSILON) continue
                for (j in column..n) augmented[row][j] -= factor * augmented[column][j]
            }
        }
        return DoubleArray(n) { augmented[it][n] }
    }
}
