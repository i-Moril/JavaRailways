package work.art1st.mineRails.core.util

object DoubleCompare {
    @JvmStatic
    fun less(a: Double, b: Double, tolerance: Double): Boolean {
        return compare(a - b, tolerance) < 0
    }

    @JvmStatic
    fun greater(a: Double, b: Double, tolerance: Double): Boolean {
        return compare(b - a, tolerance) > 0
    }

    @JvmStatic
    fun compare(value: Double, tolerance: Double): Int {
        return if (value >= 0) {
            if (value < tolerance) 0 else 1
        } else {
            if (value > -tolerance) 0 else -1
        }
    }
}
