package work.art1st.javarailways.bukkit.tool

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
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.wand.WandModeSwitch
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

interface WandModeSpec {
    val displayName: String
    val description: String
}

interface WandState<M> {
    var mode: M
}

internal fun shouldGloballyClearSelection(
    sneaking: Boolean,
    action: Action,
    modeHandlesSneakLeftClickAir: Boolean
): Boolean = sneaking && action == Action.LEFT_CLICK_AIR && !modeHandlesSneakLeftClickAir

/** Temporarily disabled; F remains the Wand mode-switch shortcut. */
private const val WAND_HELD_SLOT_MODE_SWITCH_ENABLED = false

internal fun shouldSwitchWandModeOnHeldSlotChange(
    sneaking: Boolean,
    holdingMainHandWand: Boolean
): Boolean = WAND_HELD_SLOT_MODE_SWITCH_ENABLED && sneaking && holdingMainHandWand

abstract class AbstractWand<S, M>(
    protected val plugin: BukkitPlugin,
    private val wandKey: String,
    private val material: Material,
    private val itemDisplayName: Component,
    private val itemLore: List<Component>,
    private val modes: List<M>
) : Listener where S : WandState<M>, M : Enum<M>, M : WandModeSpec {
    /** PDC is persisted by modern Paper inside the item's minecraft:custom_data component. */
    private data class BoundState<S>(val itemId: UUID, val state: S)

    protected open val modeHandlesSneakLeftClickAir: Boolean = false

    private var initialized = false
    private val playerStates = ConcurrentHashMap<UUID, BoundState<S>>()
    private val itemIdKey by lazy { NamespacedKey(plugin, "wand_instance_id") }
    private val itemStateKey by lazy { NamespacedKey(plugin, "wand_state") }

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

    protected abstract fun encodeState(state: S): String

    protected abstract fun decodeState(encoded: String): S?

    protected open fun itemDisplayNameFor(player: Player): Component = itemDisplayName

    protected open fun itemLoreFor(player: Player): List<Component> = itemLore

    protected open fun modeDisplayNameFor(player: Player, mode: M): String = mode.displayName

    protected open fun modeDescriptionFor(player: Player, mode: M): String = mode.description

    protected open fun selectionClearedMessage(player: Player): Component =
        Component.text("已清空选择", NamedTextColor.GRAY)

    fun getState(player: Player): S {
        val item = findActiveWand(player) ?: return playerStates[player.uniqueId]?.state ?: createDefaultState()
        val itemId = ensureItemId(item)
        val current = playerStates[player.uniqueId]
        if (current?.itemId == itemId) return current.state
        if (current != null) persistBoundState(player, current)
        val encoded = item.itemMeta?.persistentDataContainer?.get(itemStateKey, PersistentDataType.STRING)
        var corrupted = false
        val decoded = if (encoded == null) {
            null
        } else {
            try {
                decodeState(encoded).also { state ->
                    if (state == null) corrupted = true
                }
            } catch (failure: Exception) {
                corrupted = true
                plugin.logger.log(
                    Level.WARNING,
                    "Failed to decode wand state for player ${player.uniqueId}, item $itemId; preserving stored data",
                    failure
                )
                null
            }
        }
        if (corrupted && decoded == null) {
            plugin.logger.warning(
                "Invalid wand state for player ${player.uniqueId}, item $itemId; using an in-memory default without overwriting stored data"
            )
        }
        val state = decoded ?: createDefaultState()
        playerStates[player.uniqueId] = BoundState(itemId, state)
        if (!corrupted) persistState(player, state)
        return state
    }

    protected fun persistState(player: Player, state: S) {
        val bound = playerStates[player.uniqueId]
        if (bound != null && bound.state === state) {
            persistBoundState(player, bound)
            return
        }
        val item = findActiveWand(player) ?: return
        val replacement = BoundState(ensureItemId(item), state)
        playerStates[player.uniqueId] = replacement
        persistBoundState(player, replacement)
    }

    fun isWandItem(item: ItemStack?): Boolean {
        if (item == null || item.type != material) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(NamespacedKey(plugin, wandKey), PersistentDataType.BOOLEAN)
    }

    fun giveWand(player: Player) {
        val item = ItemStack(material)
        val meta = item.itemMeta as ItemMeta
        meta.displayName(itemDisplayNameFor(player))
        meta.persistentDataContainer.set(NamespacedKey(plugin, wandKey), PersistentDataType.BOOLEAN, true)
        meta.persistentDataContainer.set(itemIdKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        meta.persistentDataContainer.set(itemStateKey, PersistentDataType.STRING, encodeState(createDefaultState()))
        meta.lore(itemLoreFor(player))
        item.itemMeta = meta
        player.inventory.addItem(item)
    }

    @EventHandler(ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        WandModeSwitch.handle(event, ::isWandItem) { player ->
            switchToNextMode(player, getState(player))
        }
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        BukkitThreadGuardBridge.runInSimulationContext { handleInteract(event) }
    }

    private fun handleInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!isWandItem(player.inventory.itemInMainHand)) return
        if (event.hand != EquipmentSlot.HAND) return

        val state = getState(player)
        when {
            player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) -> {
                event.isCancelled = true
                showSettings(player, state)
            }
            shouldGloballyClearSelection(
                player.isSneaking,
                event.action,
                modeHandlesSneakLeftClickAir
            ) -> {
                event.isCancelled = true
                clearSelection(player, state)
                player.sendActionBar(selectionClearedMessage(player))
            }
            else -> {
                event.isCancelled = true
                handleWandInteract(player, state, event)
            }
        }
        persistState(player, state)
    }

    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        val shouldSwitchMode = shouldSwitchWandModeOnHeldSlotChange(
            sneaking = player.isSneaking,
            holdingMainHandWand = isWandItem(player.inventory.itemInMainHand)
        )
        if (!shouldSwitchMode) return
        event.isCancelled = true
        switchToNextMode(player, getState(player))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val bound = playerStates.remove(event.player.uniqueId)
        if (bound != null) {
            persistBoundState(event.player, bound)
            onPlayerQuit(event.player, bound.state)
        }
    }

    private fun switchToNextMode(player: Player, state: S) {
        if (modes.isEmpty()) return
        val currentIndex = modes.indexOf(state.mode).takeIf { it >= 0 } ?: 0
        state.mode = modes[(currentIndex + 1) % modes.size]
        clearSelection(player, state)
        persistState(player, state)
        player.sendActionBar(Component.text(
            "${modeDisplayNameFor(player, state.mode)}: ${modeDescriptionFor(player, state.mode)}",
            NamedTextColor.GREEN
        ))
    }

    protected open fun clearSelection(player: Player, state: S) {}

    protected open fun showSettings(player: Player, state: S) {
        player.sendActionBar(Component.text("该工具暂无设置", NamedTextColor.GRAY))
    }

    protected abstract fun handleWandInteract(player: Player, state: S, event: PlayerInteractEvent)

    protected open fun onPlayerQuit(player: Player, state: S) {}

    private fun findActiveWand(player: Player): ItemStack? = when {
        isWandItem(player.inventory.itemInMainHand) -> player.inventory.itemInMainHand
        isWandItem(player.inventory.itemInOffHand) -> player.inventory.itemInOffHand
        else -> player.inventory.contents.firstOrNull(::isWandItem)
    }

    private fun ensureItemId(item: ItemStack): UUID {
        val meta = item.itemMeta ?: return UUID.randomUUID()
        val existing = meta.persistentDataContainer.get(itemIdKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (existing != null) return existing
        val created = UUID.randomUUID()
        meta.persistentDataContainer.set(itemIdKey, PersistentDataType.STRING, created.toString())
        item.itemMeta = meta
        return created
    }

    private fun persistBoundState(player: Player, bound: BoundState<S>) {
        val item = player.inventory.contents.firstOrNull { candidate ->
            if (!isWandItem(candidate)) return@firstOrNull false
            candidate?.itemMeta?.persistentDataContainer
                ?.get(itemIdKey, PersistentDataType.STRING) == bound.itemId.toString()
        } ?: return
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(itemStateKey, PersistentDataType.STRING, encodeState(bound.state))
        item.itemMeta = meta
    }

    protected fun getCoreWorld(player: Player): CoreWorld? =
        plugin.worldManager.railWorlds[player.world] as? CoreWorld
}
