package work.art1st.javarailways.bukkit.render.backend

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation

/** Initial visual state used when constructing a display through the selected backend. */
data class DisplayVisualDescriptor(
    val location: Location,
    val content: DisplayVisualContent,
    val transformation: Transformation,
    val viewRange: Float,
    val interpolationDuration: Int = 0,
    val teleportDuration: Int = 0
)

sealed interface DisplayVisualContent {
    data class Item(val itemStack: ItemStack) : DisplayVisualContent
    data class Block(val blockData: BlockData) : DisplayVisualContent
}

interface DisplayVisual {
    /** Internal renderer affinity; packet visuals have no server entity. */
    val affinityEntity: org.bukkit.entity.Entity? get() = null
    fun showTo(player: Player)
    fun hideFrom(player: Player)
    fun teleport(location: Location)
    fun setTransformation(transformation: Transformation)
    fun setItemStack(itemStack: ItemStack)
    fun setBrightness(blockLight: Int, skyLight: Int)
    /** Remove the override so the client uses natural world light. */
    fun clearBrightness()
    fun addPassenger(passenger: DisplayVisual): Boolean
    fun destroy()
}

interface DisplayRenderBackend {
    fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual
}

internal fun displayViewRangeForDistance(maxBlocks: Double): Float = (maxBlocks / 64.0).toFloat()

internal fun packDisplayBrightness(blockLight: Int, skyLight: Int): Int {
    require(blockLight in 0..15 && skyLight in 0..15) {
        "Display light values must be between 0 and 15"
    }
    return (blockLight shl 4) or (skyLight shl 20)
}
