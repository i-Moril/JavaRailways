package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletionStage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import work.art1st.javarailways.core.api.util.Destruction

import io.papermc.paper.entity.TeleportFlag
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.TextDisplay
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
    private val plugin: Plugin,
    private val dispatcher: PlatformDispatcher
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
            is DisplayVisualContent.Text -> world.spawn(
                descriptor.location,
                TextDisplay::class.java
            ) { display ->
                configureCommon(display, descriptor)
                configureText(display, content.text)
            }
        }
        return NativeDisplayVisual(plugin, entity, dispatcher)
    }

    private fun configureText(display: TextDisplay, text: Component) {
        display.billboard = Display.Billboard.FIXED
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.lineWidth = Int.MAX_VALUE
        display.textOpacity = (-1).toByte()
        display.isShadowed = false
        display.isSeeThrough = false
        display.isDefaultBackground = false
        display.backgroundColor = Color.fromARGB(0)
        display.text(text)
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
    override val packetEntityId = entity.entityId
    override val packetGlowBaseline get() = ((if (entity.isVisualFire || entity.fireTicks > 0) 1 else 0) or
        (if (entity.isSneaking) 2 else 0) or (if (entity.isInvisible) 0x20 else 0) or
        (if (entity.isGlowing) 0x40 else 0)).toByte() to (entity.glowColorOverride?.asRGB() ?: -1)
    @Volatile
    private var destroyed = false
    private val destruction = Destruction()
    @Volatile private var passengerIds: List<Int> = emptyList()
    @Volatile private var passengerParent: NativeDisplayVisual? = null

    @Synchronized private fun rememberPassenger(id: Int) { if (id !in passengerIds) passengerIds = passengerIds + id }
    @Synchronized private fun forgetPassenger(id: Int) { passengerIds = passengerIds.filter { it != id } }

    override fun refreshPassengersFor(player: Player) {
        if (destroyed) return
        val captured = passengerIds.toIntArray()
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(listOf(player.uniqueId)) {
            WrapperPlayServerSetPassengers(packetEntityId, captured)
        }
    }


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

    override fun setText(text: Component) {
        if (destroyed) return
        require(entity is TextDisplay) { "Text updates require a text display visual" }
        entity.text(text)
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
        if (nativePassenger.entity.vehicle != entity) {
            nativePassenger.entity.vehicle?.removePassenger(nativePassenger.entity)
            if (!entity.addPassenger(nativePassenger.entity)) return false
        }
        nativePassenger.passengerParent?.forgetPassenger(nativePassenger.packetEntityId)
        nativePassenger.passengerParent = this
        rememberPassenger(nativePassenger.packetEntityId)
        return true
    }

    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        destroyed = true
        passengerParent?.forgetPassenger(packetEntityId)
        passengerParent = null
        passengerIds = emptyList()
        dispatcher.runForEntityCompletion(entity) { entity.remove() }
    }
}
