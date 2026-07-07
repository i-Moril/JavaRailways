package work.art1st.mineRails.bukkit.control

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.api.physics.train.Train
import java.util.UUID

object TrainKeyItem {
    private val genericKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "train_key")
    private val boundTrainIdKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "bound_train_id")
    private val boundWorldIdKey: NamespacedKey
        get() = NamespacedKey(BukkitPlugin.INSTANCE, "bound_world_id")

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
        return meta.persistentDataContainer.get(genericKey, PersistentDataType.BYTE) == 1.toByte()
    }

    fun boundTrainId(item: ItemStack?): String? {
        if (!isKey(item)) return null
        return item?.itemMeta?.persistentDataContainer?.get(boundTrainIdKey, PersistentDataType.STRING)
    }

    fun boundWorldId(item: ItemStack?): UUID? {
        if (!isKey(item)) return null
        val value = item?.itemMeta?.persistentDataContainer?.get(boundWorldIdKey, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun bind(item: ItemStack, train: Train): Boolean {
        if (!isKey(item)) return false
        item.editMeta { meta ->
            meta.persistentDataContainer.set(boundTrainIdKey, PersistentDataType.STRING, train.id)
            meta.persistentDataContainer.set(boundWorldIdKey, PersistentDataType.STRING, train.world.bukkitWorldData.bukkitWorld.uid.toString())
            meta.lore(listOf(
                Component.text("已绑定列车: ${train.id}"),
                Component.text("右键打开列车操控界面")
            ))
        }
        return true
    }

    fun unbind(item: ItemStack): Boolean {
        if (!isKey(item)) return false
        item.editMeta { meta ->
            meta.persistentDataContainer.remove(boundTrainIdKey)
            meta.persistentDataContainer.remove(boundWorldIdKey)
            meta.lore(listOf(
                Component.text("手持此钥匙坐在驾驶座上绑定列车")
            ))
        }
        return true
    }

    fun playerHasKey(player: Player): Boolean {
        return isKey(player.inventory.itemInMainHand) || isKey(player.inventory.itemInOffHand)
    }
}
