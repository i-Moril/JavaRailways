package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID

interface WandModeSpec {
    val displayName: String
    val description: String
}

interface WandState<M> {
    var mode: M
}

abstract class AbstractWand<S, M>(
    protected val plugin: BukkitPlugin,
    private val wandKey: String,
    private val material: Material,
    private val itemDisplayName: Component,
    private val itemLore: List<Component>,
    private val modes: List<M>
) : Listener where S : WandState<M>, M : Enum<M>, M : WandModeSpec {
    private var initialized = false
    private val playerStates = mutableMapOf<UUID, S>()

    fun init() {
        if (initialized) return
        initialized = true
        onBeforeRegisterEvents()
        plugin.server.pluginManager.registerEvents(this, plugin)
        onAfterRegisterEvents()
    }

    protected open fun onBeforeRegisterEvents() {}

    protected open fun onAfterRegisterEvents() {}

    protected abstract fun createDefaultState(): S

    fun getState(player: Player): S =
        playerStates.getOrPut(player.uniqueId) { createDefaultState() }

    fun isWandItem(item: ItemStack?): Boolean {
        if (item == null || item.type != material) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(NamespacedKey(plugin, wandKey), PersistentDataType.BOOLEAN)
    }

    fun giveWand(player: Player) {
        val item = ItemStack(material)
        val meta = item.itemMeta as ItemMeta
        meta.displayName(itemDisplayName)
        meta.persistentDataContainer.set(NamespacedKey(plugin, wandKey), PersistentDataType.BOOLEAN, true)
        meta.lore(itemLore)
        item.itemMeta = meta
        player.inventory.addItem(item)
    }

    @EventHandler
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) return
        event.isCancelled = true
        switchToNextMode(player, getState(player))
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!isWandItem(player.inventory.itemInMainHand)) return
        if (event.hand != EquipmentSlot.HAND) return

        val state = getState(player)
        when {
            player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) -> {
                event.isCancelled = true
                showSettings(player, state)
            }
            player.isSneaking && event.action == Action.LEFT_CLICK_AIR -> {
                event.isCancelled = true
                clearSelection(player, state)
                player.sendActionBar(Component.text("已清空选择", NamedTextColor.GRAY))
            }
            else -> {
                event.isCancelled = true
                handleWandInteract(player, state, event)
            }
        }
    }

    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        if (!player.isSneaking || !isWandItem(player.inventory.itemInMainHand)) return
        event.isCancelled = true
        switchToNextMode(player, getState(player))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val state = playerStates.remove(event.player.uniqueId)
        if (state != null) {
            onPlayerQuit(event.player, state)
        }
    }

    private fun switchToNextMode(player: Player, state: S) {
        if (modes.isEmpty()) return
        val currentIndex = modes.indexOf(state.mode).takeIf { it >= 0 } ?: 0
        state.mode = modes[(currentIndex + 1) % modes.size]
        clearSelection(player, state)
        player.sendActionBar(Component.text("${state.mode.displayName}: ${state.mode.description}", NamedTextColor.GREEN))
    }

    protected open fun clearSelection(player: Player, state: S) {}

    protected open fun showSettings(player: Player, state: S) {
        player.sendActionBar(Component.text("该工具暂无设置", NamedTextColor.GRAY))
    }

    protected abstract fun handleWandInteract(player: Player, state: S, event: PlayerInteractEvent)

    protected open fun onPlayerQuit(player: Player, state: S) {}

    protected fun getCoreWorld(player: Player): CoreWorld? =
        plugin.worldManager.railWorlds[player.world] as? CoreWorld
}
