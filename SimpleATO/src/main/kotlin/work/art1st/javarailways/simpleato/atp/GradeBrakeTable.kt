package work.art1st.javarailways.simpleato.atp

import kotlin.math.*

/** A small table of 1/(2b) for the kernel's constant pneumatic force, indexed by adverse grade. */
internal class GradeBrakeTable(private val flatDeceleration: Double, private val gravity: Double) {
    private val step = 0.0025
    private val grades = DoubleArray(801) { if (it == 400) 0.0 else -1.0 + it * step }
    private val reciprocal = DoubleArray(grades.size) { index ->
        val b = Math.nextDown(flatDeceleration + gravity * grades[index])
        if (b > 0.0) Math.nextUp(1.0 / Math.nextDown(2.0 * b)) else Double.POSITIVE_INFINITY
    }
    fun distance(speed: Double, targetSpeed: Double, grade: Double): Double {
        if (speed <= targetSpeed) return 0.0
        var index = floor((grade + 1.0) / step).toInt().coerceIn(grades.indices)
        // Floating point division may round across a boundary. Never choose a grade above input.
        while (index > 0 && grades[index] > grade) index--
        val factor = reciprocal[index]
        if (!factor.isFinite()) return Double.POSITIVE_INFINITY
        val difference = Math.nextUp(speed - targetSpeed)
        val sum = Math.nextUp(speed + targetSpeed)
        return Math.nextUp(Math.nextUp(difference * sum) * factor)
    }
}
