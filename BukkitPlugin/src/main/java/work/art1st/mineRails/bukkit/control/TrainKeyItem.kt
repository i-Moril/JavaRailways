package work.art1st.mineRails.bukkit.control

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import work.art1st.mineRails.bukkit.BukkitPlugin

object TrainKeyItem {
    private val genericKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "train_key")

    /** Legacy bound-key marker from earlier development builds; accepted as a generic key. */
    private val legacyTrainIdKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "train_key_id")

    fun create(): ItemStack {
        val item = ItemStack(Material.TRIPWIRE_HOOK)
        item.editMeta { meta ->
            meta.displayName(Component.text("列车钥匙"))
            meta.lore(listOf(
                Component.text("手持此钥匙坐在驾驶座上控制列车")
            ))
            meta.persistentDataContainer.set(genericKey, PersistentDataType.BYTE, 1)
        }
        return item
    }

    fun isKey(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        val container = meta.persistentDataContainer
        return container.get(genericKey, PersistentDataType.BYTE) == 1.toByte() ||
            container.has(legacyTrainIdKey, PersistentDataType.STRING)
    }

    fun playerHasKey(player: Player): Boolean {
        return isKey(player.inventory.itemInMainHand) || isKey(player.inventory.itemInOffHand)
    }
}
