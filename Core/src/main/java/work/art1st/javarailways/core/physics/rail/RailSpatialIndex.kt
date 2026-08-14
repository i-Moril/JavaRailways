package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Coarse 3D spatial index for immutable rail geometry.
 *
 * Rails are inserted by sampled curve points into chunk-like buckets. Query results are only
 * candidate rails; callers still run exact geometry checks afterwards.
 */
class RailSpatialIndex(
    val cellSize: Double = DEFAULT_CELL_SIZE
) {
    private data class CellKey(val x: Int, val y: Int, val z: Int)

    private val buckets: MutableMap<CellKey, MutableSet<CoreRail>> = HashMap()
    private val railCells: MutableMap<java.util.UUID, Set<CellKey>> = HashMap()

    @Synchronized
    fun addRail(rail: CoreRail) {
        removeRail(rail)
        val cells = collectCells(rail)
        railCells[rail.id] = cells
        cells.forEach { cell ->
            buckets.getOrPut(cell) { LinkedHashSet() }.add(rail)
        }
    }

    @Synchronized
    fun removeRail(rail: CoreRail) {
        railCells.remove(rail.id)?.forEach { cell ->
            buckets[cell]?.let { rails ->
                rails.remove(rail)
                if (rails.isEmpty()) buckets.remove(cell)
            }
        }
    }

    @Synchronized
    fun query(position: Vector3dc, radius: Double): Set<CoreRail> {
        if (buckets.isEmpty()) return emptySet()
        val safeRadius = radius.coerceAtLeast(0.0)
        val minX = cellCoord(position.x() - safeRadius)
        val maxX = cellCoord(position.x() + safeRadius)
        val minY = cellCoord(position.y() - safeRadius)
        val maxY = cellCoord(position.y() + safeRadius)
        val minZ = cellCoord(position.z() - safeRadius)
        val maxZ = cellCoord(position.z() + safeRadius)
        val result = LinkedHashSet<CoreRail>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    buckets[CellKey(x, y, z)]?.let(result::addAll)
                }
            }
        }
        return result
    }

    @Synchronized
    fun queryAlongRay(origin: Vector3dc, direction: Vector3dc, maxDistance: Double, radius: Double): Set<CoreRail> {
        if (buckets.isEmpty() || maxDistance < 0.0) return emptySet()
        val step = max(cellSize / 2.0, radius.coerceAtLeast(1.0))
        val samples = max(1, ceil(maxDistance / step).toInt())
        val lookupRadius = radius.coerceAtLeast(cellSize)
        val result = LinkedHashSet<CoreRail>()
        for (index in 0..samples) {
            val distance = minOf(maxDistance, index * step)
            val sample = Vector3d(origin).fma(distance, direction)
            result.addAll(query(sample, lookupRadius))
        }
        return result
    }

    @OptIn(Heavy::class)
    private fun collectCells(rail: CoreRail): Set<CellKey> {
        val cells = LinkedHashSet<CellKey>()
        rail.curve.stream().use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val point = iterator.next()
                cells.add(CellKey(cellCoord(point.position.x()), cellCoord(point.position.y()), cellCoord(point.position.z())))
            }
        }
        // Defensive fallback for pathological curves with no stream points.
        if (cells.isEmpty()) {
            val start = rail.curve.getPointAt(0.0).position
            val end = rail.curve.getPointAt(rail.curve.totalArcLength).position
            cells.add(CellKey(cellCoord(start.x()), cellCoord(start.y()), cellCoord(start.z())))
            cells.add(CellKey(cellCoord(end.x()), cellCoord(end.y()), cellCoord(end.z())))
        }
        return cells
    }

    private fun cellCoord(value: Double): Int = floor(value / cellSize).toInt()

    companion object {
        private const val DEFAULT_CELL_SIZE = 16.0
    }
}
