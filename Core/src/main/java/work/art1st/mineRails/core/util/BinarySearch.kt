package work.art1st.mineRails.core.util


object BinarySearch {
    /**
     * 要求cmp从小到大; 输出绝对值较小的。
     * 左闭右开区间
     */
    fun <R> searchIntegerBoundary(
        f: (Int) -> R,
        cmp: (R) -> Double,
        start: Int,
        end: Int
    ) : Pair<Int, R> {
        val upperBound = end
        var start = start
        var end = end
        while (true) {
            if (end - start == 1) {
                val yStart = f(start)
                if (end == upperBound) { return Pair(start, yStart) }
                val yEnd = f(end)
                return if (-cmp(yStart) > cmp(yEnd)) Pair(end, yEnd) else Pair(start, yStart)
            }
            val midValue = start + (end - start) / 2
            val y = f(midValue)
            val cmpResult = cmp(y)
            if (cmpResult > 0) {
                start = midValue
            } else if (cmpResult < 0) {
                end = midValue
            } else {
                return Pair(midValue, y)
            }
        }
    }

    fun searchIntegerBoundary(
        f: (Int) -> Double,
        start: Int,
        end: Int
    ) : Int {
        return searchIntegerBoundary(f, {x -> x}, start, end).a
    }

    fun <R> searchList(
        list: List<R>,
        cmp: (R) -> Double
    ) : Int {
        return searchIntegerBoundary({i -> list[i]}, cmp, 0, list.size).a
    }
}
