package work.art1st.javarailways.tunnelbridgebuilder.wand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class BlockWandItem(private val plugin: JavaPlugin) {
    private val markerKey = NamespacedKey(plugin, "block_wand")
    private val instanceIdKey = NamespacedKey(plugin, "block_wand_instance_id")
    private val stateKey = NamespacedKey(plugin, "block_wand_state")

    fun create(): ItemStack = ItemStack(Material.BLAZE_ROD).also { item ->
        val meta = item.itemMeta
        meta.displayName(Component.text("Tunnel/Bridge Builder Wand", NamedTextColor.GOLD))
        meta.lore(
            listOf(
                Component.text("右键轨道：按横断面替换方块", NamedTextColor.GRAY),
                Component.text("Shift + 右键：编辑横断面", NamedTextColor.GRAY),
                Component.text("/jr undo：撤销最近一次替换", NamedTextColor.DARK_GRAY)
            )
        )
        meta.persistentDataContainer.set(markerKey, PersistentDataType.BYTE, 1)
        meta.persistentDataContainer.set(instanceIdKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        meta.persistentDataContainer.set(
            stateKey,
            PersistentDataType.STRING,
            BlockWandStateCodec.encode(BlockWandState.createDefault())
        )
        item.itemMeta = meta
    }

    fun isWand(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.get(markerKey, PersistentDataType.BYTE) == 1.toByte()

    fun load(item: ItemStack): BlockWandState? {
        if (!isWand(item)) return null
        val encoded = item.itemMeta.persistentDataContainer.get(stateKey, PersistentDataType.STRING)
            ?: return BlockWandState.createDefault()
        return runCatching { BlockWandStateCodec.decode(encoded) }
            .onFailure { plugin.logger.log(java.util.logging.Level.WARNING, "Invalid BlockWand state", it) }
            .getOrNull()
    }

    fun save(item: ItemStack, state: BlockWandState) {
        require(isWand(item)) { "Cannot save BlockWand state into a different item" }
        val meta = item.itemMeta
        meta.persistentDataContainer.set(stateKey, PersistentDataType.STRING, BlockWandStateCodec.encode(state))
        item.itemMeta = meta
    }

    fun findByInstance(player: Player, instanceId: UUID): ItemStack? = player.inventory.contents.firstOrNull { item ->
        isWand(item) && item?.itemMeta?.persistentDataContainer
            ?.get(instanceIdKey, PersistentDataType.STRING) == instanceId.toString()
    }

    fun instanceId(item: ItemStack): UUID? = item.itemMeta.persistentDataContainer
        .get(instanceIdKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
