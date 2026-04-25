package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData

/**
 * 转向架渲染扩展。
 * 转向架通常由 CarExtension 渲染，但独立的转向架模型也可使用此扩展。
 */
class BogieExtension : TrainComponentExtension<Bogie>() {
    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun buildModel() {
        val bogieConfig = BukkitPlugin.carModelConfig.bogie
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld

        // 转向架框架
        val frame = bogieConfig.frame
        addDisplayEntity(bukkitWorld, frame.material,
            Vector3d(frame.offset.x, frame.offset.y, frame.offset.z),
            Vector3d(frame.scale.x, frame.scale.y, frame.scale.z))

        // 轨道
        bogieConfig.rail?.let { rail ->
            addDisplayEntity(bukkitWorld, rail.material,
                Vector3d(rail.offset.x, rail.offset.y, rail.offset.z),
                Vector3d(rail.scale.x, rail.scale.y, rail.scale.z))
        }
    }

    override fun updateModel() {
        val bogiePos = self.position
        val direction = self.direction
        val normal = self.normal

        val right = Vector3d()
        normal.cross(direction, right).normalize()
        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), direction.x(),
            right.y(), normal.y(), direction.y(),
            right.z(), normal.z(), direction.z()
        )
        val bogieRotation = Quaterniond().setFromNormalized(rotationMatrix)

        val bogieConfig = BukkitPlugin.carModelConfig.bogie
        val frame = bogieConfig.frame

        // 框架
        updateItemDisplay(displayEntities[0],
            Vector3d(frame.offset.x, frame.offset.y, frame.offset.z),
            Vector3d(frame.scale.x, frame.scale.y, frame.scale.z),
            bogiePos, bogieRotation)

        // 轨道
        if (displayEntities.size > 1) {
            val rail = bogieConfig.rail ?: return
            updateItemDisplay(displayEntities[1],
                Vector3d(rail.offset.x, rail.offset.y, rail.offset.z),
                Vector3d(rail.scale.x, rail.scale.y, rail.scale.z),
                bogiePos, bogieRotation)
        }
    }

    override fun destroyModel() {
        for (entity in displayEntities) {
            entity.remove()
        }
        displayEntities.clear()
    }

    private val displayEntities = mutableListOf<ItemDisplay>()

    private fun addDisplayEntity(
        bukkitWorld: org.bukkit.World,
        materialName: String,
        localPos: Vector3dc,
        localScale: Vector3dc
    ) {
        val material = try { Material.valueOf(materialName.uppercase()) } catch (e: Exception) { Material.STONE }
        val entity = bukkitWorld.spawnEntity(
            Location(bukkitWorld, 0.0, 0.0, 0.0),
            EntityType.ITEM_DISPLAY
        ) as ItemDisplay
        entity.isPersistent = false
        entity.setItemStack(ItemStack(material))
        entity.setTransformation(
            Transformation(
                Vector3f(localPos.x().toFloat(), localPos.y().toFloat(), localPos.z().toFloat()),
                org.joml.Quaternionf(1f, 0f, 0f, 0f),
                Vector3f(localScale.x().toFloat(), localScale.y().toFloat(), localScale.z().toFloat()),
                org.joml.Quaternionf(1f, 0f, 0f, 0f)
            )
        )
        displayEntities.add(entity)
    }

    private fun updateItemDisplay(
        entity: ItemDisplay,
        localPos: Vector3dc,
        localScale: Vector3dc,
        bogiePos: Vector3dc,
        bogieRotation: Quaterniond
    ) {
        val rotatedOffset = Vector3d(localPos.x(), localPos.y(), localPos.z())
        bogieRotation.transform(rotatedOffset)
        val worldPos = Vector3d(bogiePos.x(), bogiePos.y(), bogiePos.z()).add(rotatedOffset)

        entity.setTransformation(
            Transformation(
                Vector3f(worldPos.x().toFloat(), worldPos.y().toFloat(), worldPos.z().toFloat()),
                bogieRotation.get(org.joml.Quaternionf()),
                Vector3f(localScale.x().toFloat(), localScale.y().toFloat(), localScale.z().toFloat()),
                org.joml.Quaternionf(1f, 0f, 0f, 0f)
            )
        )
    }
}
