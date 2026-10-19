package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction

import io.papermc.paper.entity.TeleportFlag
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation

class NativeDisplayRenderBackend(
    private val plugin: Plugin
) : DisplayRenderBackend {
    override fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual {
        val entity = when (val content = descriptor.content) {
            is DisplayVisualContent.Item -> world.spawn(
                descriptor.location,
                ItemDisplay::class.java
            ) { display ->
                configureCommon(display, descriptor)
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                display.setItemStack(content.itemStack.clone())
            }

            is DisplayVisualContent.Block -> world.spawn(
                descriptor.location,
                BlockDisplay::class.java
            ) { display ->
                configureCommon(display, descriptor)
                display.block = content.blockData.clone()
            }
        }
        return NativeDisplayVisual(plugin, entity)
    }

    private fun configureCommon(display: Display, descriptor: DisplayVisualDescriptor) {
        display.isPersistent = false
        display.isVisibleByDefault = false
        display.interpolationDelay = 0
        display.interpolationDuration = descriptor.interpolationDuration
        display.teleportDuration = descriptor.teleportDuration
        display.viewRange = descriptor.viewRange
        display.transformation = descriptor.transformation
    }
}

internal class NativeDisplayVisual(
    private val plugin: Plugin,
    private val entity: Display,
    private val dispatcher: PlatformDispatcher = BukkitPlugin.INSTANCE.platformDispatcher
) : DisplayVisual {
    override val affinityEntity get() = entity
    @Volatile
    private var destroyed = false
    private val destruction = Destruction()

    @Synchronized
    override fun showTo(player: Player) {
        if (!destroyed) player.showEntity(plugin, entity)
    }

    @Synchronized
    override fun hideFrom(player: Player) {
        if (!destroyed) player.hideEntity(plugin, entity)
    }

    override fun teleport(location: Location) {
        if (!destroyed) entity.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
    }

    override fun setTransformation(transformation: Transformation) {
        if (!destroyed) entity.transformation = transformation
    }

    override fun setItemStack(itemStack: ItemStack) {
        if (!destroyed) (entity as? ItemDisplay)?.setItemStack(itemStack.clone())
    }

    override fun setBrightness(blockLight: Int, skyLight: Int) {
        packDisplayBrightness(blockLight, skyLight)
        if (!destroyed) entity.brightness = Display.Brightness(blockLight, skyLight)
    }

    override fun clearBrightness() {
        if (!destroyed) entity.brightness = null
    }

    override fun addPassenger(passenger: DisplayVisual): Boolean {
        if (destroyed) return false
        val nativePassenger = passenger as? NativeDisplayVisual
            ?: throw IllegalArgumentException("Cannot mix native and packet display passengers")
        if (nativePassenger.destroyed) return false
        if (nativePassenger.entity.vehicle == entity) return true
        nativePassenger.entity.vehicle?.removePassenger(nativePassenger.entity)
        return entity.addPassenger(nativePassenger.entity)
    }

    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        destroyed = true
        dispatcher.runForEntityCompletion(entity) { entity.remove() }
    }
}
