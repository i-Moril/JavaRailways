package work.art1st.mineRails.core.util


object BinarySearch {
    /**
     * 要求cmp从小到大; 输出绝对值较小的。
     * 左闭右开区间
     */
    fun <R> searchIntegerBoundary(
        start: Int,
        end: Int,
        f: (Int) -> R,
        cmp: (R) -> Double,
    ) : Triple<Int, R, Double> {
        val upperBound = end
        var start = start
        var end = end
        while (true) {
            if (end - start == 1) {
                val yStart = f(start)
                val vStart = cmp(yStart)
                if (end == upperBound) { return Triple(start, yStart, vStart) }
                val yEnd = f(end)
                val vEnd = cmp(yEnd)
                return if (-vStart > vEnd) Triple(end, yEnd, vEnd) else Triple(start, yStart, vStart)
            }
            val midValue = start + (end - start) / 2
            val y = f(midValue)
            val cmpResult = cmp(y)
            if (cmpResult > 0) {
                end = midValue
            } else if (cmpResult < 0) {
                start = midValue
            } else {
                return Triple(midValue, y, cmpResult)
            }
        }
    }

    fun searchIntegerBoundary(
        start: Int,
        end: Int,
        f: (Int) -> Double
    ) : Pair<Int, Double> {
        val result = searchIntegerBoundary(start, end, f, {x -> x})
        return Pair(result.first, result.third)
    }

    fun <R> searchList(
        list: List<R>,
        cmp: (R) -> Double
    ) : Pair<Int, Double> {
        val result = searchIntegerBoundary(0, list.size, {i -> list[i]}, cmp)
        return Pair(result.first, result.third)
    }
}
