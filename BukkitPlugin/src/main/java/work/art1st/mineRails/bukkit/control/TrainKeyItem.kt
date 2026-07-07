package work.art1st.mineRails.bukkit.control

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.train.Train

object TrainKeyItem {
    private val trainIdKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "train_key_id")

    fun create(train: Train): ItemStack {
        val item = ItemStack(Material.TRIPWIRE_HOOK)
        item.editMeta { meta ->
            meta.displayName(Component.text("列车钥匙 ${train.id}"))
            meta.lore(listOf(
                Component.text("Train: ${train.id}"),
                Component.text("手持此钥匙坐在驾驶座上控制列车")
            ))
            meta.persistentDataContainer.set(trainIdKey, PersistentDataType.STRING, train.id)
        }
        return item
    }

    fun trainId(item: ItemStack?): String? {
        if (item == null || item.type.isAir) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(trainIdKey, PersistentDataType.STRING)
    }

    fun playerHasKeyFor(player: Player, train: Train): Boolean {
        return trainId(player.inventory.itemInMainHand) == train.id ||
            trainId(player.inventory.itemInOffHand) == train.id
    }
}
