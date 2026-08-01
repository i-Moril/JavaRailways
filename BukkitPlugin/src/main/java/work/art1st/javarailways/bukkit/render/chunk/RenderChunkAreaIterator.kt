package work.art1st.javarailways.bukkit.render.chunk

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Vibe
 */
class RenderChunkAreaIterator(
    private val px: Double,
    private val pz: Double,
    private val radius: Double,
    private val chunkSize: Double
) : Iterator<RenderChunk> {

    private val radiusSq = radius * radius
    // 确定 X 的搜索范围
    private val xMin = floor((px - radius) / chunkSize).toInt()
    private val xMax = floor((px + radius) / chunkSize).toInt()

    private var curX = xMin
    private var curZMin = 0
    private var curZMax = -1 // 初始设为无效范围触发第一次计算
    private var curZ = 0

    init {
        findNextValidXRange()
    }

    private fun findNextValidXRange() {
        while (curX <= xMax) {
            // 计算当前列 x 对应的区块边界
            val bX1 = curX * chunkSize
            val bX2 = bX1 + chunkSize

            // 找到该列中，圆能覆盖到的 Z 范围
            // 算法：对于固定的 X 范围，寻找满足 (dx^2 + dz^2 <= r^2) 的 Z 区间
            val dx = when {
                px < bX1 -> bX1 - px
                px > bX2 -> px - bX2
                else -> 0.0
            }

            val dzMaxAllowed = sqrt(max(0.0, radiusSq - dx * dx))

            curZMin = floor((pz - dzMaxAllowed) / chunkSize).toInt()
            curZMax = floor((pz + dzMaxAllowed) / chunkSize).toInt()

            if (curZMin <= curZMax) {
                curZ = curZMin
                break
            }
            curX++
        }
    }

    override fun hasNext(): Boolean = curX <= xMax && curZ <= curZMax

    override fun next(): RenderChunk {
        val result = RenderChunk(curX, curZ)

        curZ++
        if (curZ > curZMax) {
            curX++
            findNextValidXRange()
        }

        return result
    }
}