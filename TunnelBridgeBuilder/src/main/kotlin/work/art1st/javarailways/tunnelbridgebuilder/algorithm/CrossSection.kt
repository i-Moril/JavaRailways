package work.art1st.javarailways.tunnelbridgebuilder.algorithm

/** Operation assigned to one cell of a two-dimensional cross-section template. */
sealed interface SectionOperation<out B>

data object Ignore : SectionOperation<Nothing>
data object Carve : SectionOperation<Nothing>
data class Place<B>(val block: B) : SectionOperation<B>

data class SectionCoordinate(val column: Int, val row: Int)

/**
 * Immutable rectangular template. Rows run from top to bottom and columns from left to right.
 * [anchor] is the cell whose center follows the center line.
 */
class CrossSection<B>(
    rows: List<List<SectionOperation<B>>>,
    val anchor: SectionCoordinate
) {
    val rows: List<List<SectionOperation<B>>> = rows.map(List<SectionOperation<B>>::toList)
    val height: Int = this.rows.size
    val width: Int = this.rows.firstOrNull()?.size ?: 0

    init {
        require(height > 0) { "Cross-section must contain at least one row" }
        require(width > 0) { "Cross-section must contain at least one column" }
        require(this.rows.all { it.size == width }) { "Cross-section rows must have equal width" }
        require(anchor.column in 0 until width && anchor.row in 0 until height) {
            "Cross-section anchor must be inside the template"
        }
    }

    operator fun get(column: Int, row: Int): SectionOperation<B>? =
        if (column in 0 until width && row in 0 until height) rows[row][column] else null

    internal val minHorizontalOffset: Double = -anchor.column - 0.5
    internal val maxHorizontalOffset: Double = width - anchor.column - 0.5
    internal val minVerticalOffset: Double = anchor.row - height + 0.5
    internal val maxVerticalOffset: Double = anchor.row + 0.5
}

data class BlockPosition(val x: Int, val y: Int, val z: Int) {
    val center: Vec3 get() = Vec3(x + 0.5, y + 0.5, z + 0.5)
}

data class BlockPositionChange<B>(
    val position: BlockPosition,
    val operation: SectionOperation<B>
)
