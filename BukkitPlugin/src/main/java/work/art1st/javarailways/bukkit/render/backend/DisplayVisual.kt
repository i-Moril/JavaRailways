package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletionStage
import net.kyori.adventure.text.Component
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
    data class Text(val text: Component) : DisplayVisualContent
}

interface DisplayVisual {
    /** Actual client entity identity, captured on its owner; absent only for non-packet test visuals. */
    val packetEntityId: Int? get() = null
    /** Owner-captured initial shared entity flags and display glow color. */
    val packetGlowBaseline: Pair<Byte, Int> get() = 0.toByte() to -1
    /** Internal renderer affinity; packet visuals have no server entity. */
    val affinityEntity: org.bukkit.entity.Entity? get() = null
    fun showTo(player: Player)
    fun hideFrom(player: Player)
    fun teleport(location: Location)
    fun setTransformation(transformation: Transformation)
    fun setItemStack(itemStack: ItemStack)
    /** Replace text, including empty text to clear. A live non-text visual rejects this operation. */
    fun setText(text: Component)
    fun setBrightness(blockLight: Int, skyLight: Int)
    /** Remove the override so the client uses natural world light. */
    fun clearBrightness()
    fun addPassenger(passenger: DisplayVisual): Boolean
    /** Replays owner-captured passenger IDs after late child spawn; caller owns the viewer. */
    fun refreshPassengersFor(player: Player)
    /** Completes when backend removal finishes; repeated calls retain the same outcome. */
    fun destroy(): CompletionStage<Void>
}

interface DisplayRenderBackend {
    /**
     * True only when every visual owned by this backend is non-persistent or packet-only, so an
     * irreversible whole-server stop may hand physical disappearance to server/client teardown.
     */
    val supportsTerminalServerStopRetirement: Boolean get() = false
    fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual
}

internal fun displayViewRangeForDistance(maxBlocks: Double): Float = (maxBlocks / 64.0).toFloat()

internal fun packDisplayBrightness(blockLight: Int, skyLight: Int): Int {
    require(blockLight in 0..15 && skyLight in 0..15) {
        "Display light values must be between 0 and 15"
    }
    return (blockLight shl 4) or (skyLight shl 20)
}
