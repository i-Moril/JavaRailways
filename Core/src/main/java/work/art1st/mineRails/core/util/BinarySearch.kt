package work.art1st.mineRails.core.util


object BinarySearch {
    fun <T, R> search(
        f: (T) -> R,
        cmp: (R) -> Int, start: T, end: T,
        mid: (T, T) -> T,
        endingCriterion: (T, T) -> Boolean
    ): R? {
        var start = start
        var end = end
        while (true) {
            if (endingCriterion(start, end)) {
                return null
            }
            val midValue = mid(start, end)
            val y = f(midValue)
            val cmpResult: Int = cmp(y)
            if (cmpResult > 0) {
                start = midValue
            } else if (cmpResult < 0) {
                end = midValue
            } else {
                return y
            }
        }
    }

    fun <R> searchDouble(
        f: (Double) -> R,
        cmp: (R) -> Int,
        start: Double,
        end: Double,
        tolerance: Double
    ): R? {
        return search<Double, R>(
            f, cmp, start, end,
            { a: Double, b: Double -> a + (b - a) / 2 },
            { a: Double, b: Double -> (b - a) < tolerance }
        )
    }

    fun searchDouble(
         cmp: (Double) -> Int,
         start: Double,
         end: Double,
         tolerance: Double
    ): Double? {
        return searchDouble<Double>(
            {x -> x}, cmp, start, end, tolerance
        )
    }

    fun <R> searchInteger(
        f: (Int) -> R,
        cmp: (R) -> Int,
        start: Int,
        end: Int
    ): R? {
        return search<Int, R>(
            f, cmp, start, end,
            { a: Int, b: Int -> a + (b - a) / 2 },
            { a: Int, b: Int -> b - a <= 1 }
        )
    }

    fun searchInteger(
        cmp: (Int) -> Int,
        start: Int,
        end: Int
    ): Int? {
        return searchInteger({i -> i}, cmp, start, end)
    }
}
