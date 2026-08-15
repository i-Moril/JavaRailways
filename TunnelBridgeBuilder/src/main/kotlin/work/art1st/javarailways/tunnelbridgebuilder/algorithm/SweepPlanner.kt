package work.art1st.javarailways.tunnelbridgebuilder.algorithm

import kotlin.math.ceil
import kotlin.math.floor

/** Pure output-driven voxel sweep. It plans changes but never reads or mutates a Minecraft world. */
object SweepPlanner {
    fun <B> plan(
        section: CrossSection<B>,
        centerLine: SampledCenterLine
    ): List<BlockPositionChange<B>> {
        val bounds = sweptBounds(section, centerLine)
        val result = ArrayList<BlockPositionChange<B>>()

        for (x in bounds.minX..bounds.maxX) {
            for (y in bounds.minY..bounds.maxY) {
                for (z in bounds.minZ..bounds.maxZ) {
                    val position = BlockPosition(x, y, z)
                    val point = position.center
                    if (!insideEndPlanes(point, centerLine)) continue

                    val nearest = centerLine.nearest(point)
                    val delta = point - nearest.frame.position
                    val localColumn = delta dot nearest.frame.right
                    val localRowUp = delta dot nearest.frame.normal
                    val column = floor(localColumn + section.anchor.column + 0.5).toInt()
                    val row = floor(section.anchor.row - localRowUp + 0.5).toInt()
                    val operation = section[column, row] ?: continue
                    if (operation !== Ignore) {
                        val cellCenter = nearest.frame.position +
                            nearest.frame.right * (column - section.anchor.column).toDouble() +
                            nearest.frame.normal * (section.anchor.row - row).toDouble()
                        result += BlockPositionChange(position, operation, cellCenter)
                    }
                }
            }
        }
        return result
    }

    private fun insideEndPlanes(point: Vec3, centerLine: SampledCenterLine): Boolean {
        val afterStart = (point - centerLine.start.position) dot centerLine.start.tangent
        if (afterStart < -END_PLANE_EPSILON) return false
        val beforeEnd = (point - centerLine.end.position) dot centerLine.end.tangent
        return beforeEnd <= END_PLANE_EPSILON
    }

    private fun <B> sweptBounds(section: CrossSection<B>, centerLine: SampledCenterLine): BlockBounds {
        val corners = centerLine.frames.flatMap { frame ->
            listOf(
                frame.position + frame.right * section.minHorizontalOffset + frame.normal * section.minVerticalOffset,
                frame.position + frame.right * section.minHorizontalOffset + frame.normal * section.maxVerticalOffset,
                frame.position + frame.right * section.maxHorizontalOffset + frame.normal * section.minVerticalOffset,
                frame.position + frame.right * section.maxHorizontalOffset + frame.normal * section.maxVerticalOffset
            )
        }
        return BlockBounds(
            minX = floor(corners.minOf(Vec3::x) - BOUNDS_MARGIN).toInt(),
            maxX = ceil(corners.maxOf(Vec3::x) + BOUNDS_MARGIN).toInt() - 1,
            minY = floor(corners.minOf(Vec3::y) - BOUNDS_MARGIN).toInt(),
            maxY = ceil(corners.maxOf(Vec3::y) + BOUNDS_MARGIN).toInt() - 1,
            minZ = floor(corners.minOf(Vec3::z) - BOUNDS_MARGIN).toInt(),
            maxZ = ceil(corners.maxOf(Vec3::z) + BOUNDS_MARGIN).toInt() - 1
        )
    }

    private data class BlockBounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int
    )

    private const val END_PLANE_EPSILON = 1.0e-9
    private const val BOUNDS_MARGIN = 0.5
}
