package work.art1st.javarailways.tunnelbridgebuilder.wand

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface WandCell {
    @Serializable
    @SerialName("ignore")
    data object Ignore : WandCell

    @Serializable
    @SerialName("air")
    data object Air : WandCell

    @Serializable
    @SerialName("material")
    data class Material(val key: String) : WandCell {
        init {
            require(key.isNotBlank()) { "Material key must not be blank" }
        }
    }
}

@Serializable
data class GridCell(val column: Int, val row: Int)

@Serializable
data class BlockWandState(
    val cells: MutableList<WandCell>,
    val primaryRailCell: GridCell? = null,
    val secondaryRailCells: Set<GridCell> = emptySet()
) {
    init {
        require(cells.size == CELL_COUNT) { "BlockWand state must contain $CELL_COUNT cells" }
        require(primaryRailCell == null || primaryRailCell.isInsideEditor()) {
            "Primary rail cell must be inside the $WIDTH x $HEIGHT editor"
        }
        require(secondaryRailCells.all(GridCell::isInsideEditor)) {
            "Secondary rail cells must be inside the $WIDTH x $HEIGHT editor"
        }
        require(primaryRailCell !in secondaryRailCells) {
            "Primary rail cell cannot also be a secondary rail cell"
        }
    }

    val width: Int get() = WIDTH
    val height: Int get() = HEIGHT

    operator fun get(slot: Int): WandCell = cells[slot]

    fun withCell(slot: Int, cell: WandCell): BlockWandState {
        require(slot in 0 until CELL_COUNT)
        return copy(cells = cells.toMutableList().also { it[slot] = cell })
    }

    fun toggleRailCell(slot: Int): BlockWandState {
        require(slot in 0 until CELL_COUNT)
        val rail = cellOf(slot)
        val clearedCells = cells.toMutableList().also { it[slot] = WandCell.Air }
        return when {
            rail == primaryRailCell -> {
                val promoted = secondaryRailCells.sortedWith(compareBy(GridCell::row, GridCell::column)).firstOrNull()
                copy(
                    cells = clearedCells,
                    primaryRailCell = promoted,
                    secondaryRailCells = promoted?.let(secondaryRailCells::minus) ?: emptySet()
                )
            }
            rail in secondaryRailCells -> copy(
                cells = clearedCells,
                secondaryRailCells = secondaryRailCells - rail
            )
            primaryRailCell == null -> copy(cells = clearedCells, primaryRailCell = rail)
            else -> copy(cells = clearedCells, secondaryRailCells = secondaryRailCells + rail)
        }
    }

    fun makePrimaryRailCell(slot: Int): BlockWandState {
        require(slot in 0 until CELL_COUNT)
        val rail = cellOf(slot)
        if (rail == primaryRailCell) return withCell(slot, WandCell.Air)
        val formerPrimary = primaryRailCell
        return copy(
            cells = cells.toMutableList().also { it[slot] = WandCell.Air },
            primaryRailCell = rail,
            secondaryRailCells = (secondaryRailCells - rail) + listOfNotNull(formerPrimary)
        )
    }

    fun isRailCell(slot: Int): Boolean = cellOf(slot).let { it == primaryRailCell || it in secondaryRailCells }

    fun isPrimaryRailCell(slot: Int): Boolean = cellOf(slot) == primaryRailCell

    fun isSecondaryRailCell(slot: Int): Boolean = cellOf(slot) in secondaryRailCells

    private fun cellOf(slot: Int): GridCell = GridCell(slot % WIDTH, slot / WIDTH)

    companion object {
        const val WIDTH = 8
        const val HEIGHT = 5
        const val CELL_COUNT = WIDTH * HEIGHT
        const val INVENTORY_SIZE = 9 * 6

        fun createDefault(): BlockWandState = BlockWandState(
            cells = MutableList(CELL_COUNT) { WandCell.Ignore }
        )

        /** Maps an editor coordinate to the inventory, skipping the left axis column. */
        fun slotOf(cell: GridCell): Int {
            require(cell.isInsideEditor())
            return cell.row * 9 + cell.column + 1
        }

        /** Returns null for the left and bottom marker axes. */
        fun cellAtSlot(slot: Int): GridCell? {
            if (slot !in 0 until INVENTORY_SIZE) return null
            val inventoryRow = slot / 9
            val inventoryColumn = slot % 9
            if (inventoryColumn == 0 || inventoryRow == 5) return null
            return GridCell(inventoryColumn - 1, inventoryRow)
        }
    }
}

private fun GridCell.isInsideEditor(): Boolean =
    column in 0 until BlockWandState.WIDTH && row in 0 until BlockWandState.HEIGHT

object BlockWandStateCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(state: BlockWandState): String = json.encodeToString(state)

    fun decode(encoded: String): BlockWandState = json.decodeFromString(encoded)
}
