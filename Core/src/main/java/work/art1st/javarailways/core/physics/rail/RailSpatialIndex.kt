package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.joml.Vector3dc
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
    internal data class CellKey(val x: Int, val y: Int, val z: Int)

    /** Immutable coarse geometry prepared without reading this index's live buckets. */
    internal data class PreparedFootprint(val cells: Set<CellKey>)

    private val buckets: MutableMap<CellKey, MutableSet<CoreRail>> = HashMap()
    private val railCells: MutableMap<java.util.UUID, Set<CellKey>> = HashMap()

    @Synchronized
    internal fun addRail(rail: CoreRail, footprint: PreparedFootprint) {
        removeRail(rail)
        val cells = footprint.cells
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

    /** Rails occupying this rail's cells or immediately adjacent cells; exact checks remain mandatory. */
    @Synchronized
    internal fun candidatesFor(rail: CoreRail): Set<CoreRail> {
        val cells = railCells[rail.id] ?: return emptySet()
        val result = LinkedHashSet<CoreRail>()
        cells.forEach { cell ->
            neighbourCells(cell).forEach { key -> buckets[key]?.let(result::addAll) }
        }
        result.remove(rail)
        return result
    }

    /** Captures only rails in this footprint's neighbouring cells; callers still run exact checks. */
    @Synchronized
    internal fun candidatesFor(footprint: PreparedFootprint): Set<CoreRail> {
        val result = LinkedHashSet<CoreRail>()
        footprint.cells.forEach { cell ->
            neighbourCells(cell).forEach { key -> buckets[key]?.let(result::addAll) }
        }
        return result
    }

    /** Pure geometry preparation; safe to call before taking the world topology read lock. */
    internal fun prepareFootprint(geometryPoints: List<Vector3dc>): PreparedFootprint =
        PreparedFootprint(collectCells(geometryPoints).toSet())

    private fun collectCells(geometryPoints: List<Vector3dc>): Set<CellKey> {
        val cells = LinkedHashSet<CellKey>()
        var previous: Vector3dc? = null
        geometryPoints.forEach { current ->
            val before = previous
            if (before == null) {
                cells.add(cellAt(current))
            } else {
                addSegmentCells(cells, before, current)
            }
            previous = current
        }
        require(cells.isNotEmpty()) { "rail geometry must contain at least one point" }
        return cells
    }

    private fun addSegmentCells(cells: MutableSet<CellKey>, start: Vector3dc, end: Vector3dc) {
        val largestAxisDistance = maxOf(
            kotlin.math.abs(end.x() - start.x()),
            kotlin.math.abs(end.y() - start.y()),
            kotlin.math.abs(end.z() - start.z())
        )
        val steps = max(1, ceil(largestAxisDistance / (cellSize / 2.0)).toInt())
        for (index in 0..steps) {
            val fraction = index.toDouble() / steps
            cells.add(CellKey(
                cellCoord(start.x() + (end.x() - start.x()) * fraction),
                cellCoord(start.y() + (end.y() - start.y()) * fraction),
                cellCoord(start.z() + (end.z() - start.z()) * fraction)
            ))
        }
    }

    private fun cellAt(position: Vector3dc): CellKey =
        CellKey(cellCoord(position.x()), cellCoord(position.y()), cellCoord(position.z()))

    private fun cellCoord(value: Double): Int = floor(value / cellSize).toInt()

    companion object {
        private const val DEFAULT_CELL_SIZE = 16.0

        /** One cell and its 26 neighbours, shared by every broad-phase query. */
        internal fun neighbourCells(cell: CellKey): Sequence<CellKey> = sequence {
            for (x in cell.x - 1..cell.x + 1) {
                for (y in cell.y - 1..cell.y + 1) {
                    for (z in cell.z - 1..cell.z + 1) {
                        yield(CellKey(x, y, z))
                    }
                }
            }
        }
    }
}
