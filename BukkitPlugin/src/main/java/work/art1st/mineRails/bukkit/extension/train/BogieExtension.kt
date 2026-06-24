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
        val bogieConfig = BukkitPlugin.INSTANCE.carModelConfig.bogie
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
        val bogieRotationFinal = currentBogieRotation()

        val bogieConfig = BukkitPlugin.INSTANCE.carModelConfig.bogie
        val frame = bogieConfig.frame

        // 框架
        updateItemDisplay(displayEntities[0],
            Vector3d(frame.offset.x, frame.offset.y, frame.offset.z),
            Vector3d(frame.scale.x, frame.scale.y, frame.scale.z),
            bogiePos, bogieRotationFinal)

        // 轨道
        if (displayEntities.size > 1) {
            val rail = bogieConfig.rail ?: return
            updateItemDisplay(displayEntities[1],
                Vector3d(rail.offset.x, rail.offset.y, rail.offset.z),
                Vector3d(rail.scale.x, rail.scale.y, rail.scale.z),
                bogiePos, bogieRotationFinal)
        }
    }

    private fun currentBogieRotation(): Quaterniond {
        val direction = self.direction
        val normal = self.normal
        val right = Vector3d()
        // right = normal × direction（右手系）
        normal.cross(direction, right).normalize()

        // Matrix3d(row0, row1, row2): Row0=(right.x, normal.x, direction.x), ...
        // Columns = [right, normal, direction]
        // 然后取共轭（取反 X 轴）：q' = (w, -x, y, z)，将绕 Y 轴旋转方向取反
        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), direction.x(),
            right.y(), normal.y(), direction.y(),
            right.z(), normal.z(), direction.z()
        )
        val bogieRotation = Quaterniond().setFromNormalized(rotationMatrix)
        return Quaterniond(bogieRotation.w(), -bogieRotation.x(), bogieRotation.y(), bogieRotation.z())
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
        val bogieRotation = currentBogieRotation()
        val rotatedOffset = Vector3d(localPos.x(), localPos.y(), localPos.z())
        bogieRotation.transform(rotatedOffset)
        val worldPos = Vector3d(self.position.x(), self.position.y(), self.position.z()).add(rotatedOffset)
        val entity = bukkitWorld.spawnEntity(
            Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z()),
            EntityType.ITEM_DISPLAY
        ) as ItemDisplay
        entity.isPersistent = false
        entity.setItemStack(ItemStack(material))
        entity.setTransformation(
            Transformation(
                Vector3f(0f, 0f, 0f),
                bogieRotation.get(org.joml.Quaternionf()),
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

        // Display entities are tracked by their real server-side entity location.
        // Keep that location at the visible position; otherwise a bogie far from
        // world origin may never be sent to nearby players even if the transform
        // translation visually points there.
        entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))

        entity.setTransformation(
            Transformation(
                Vector3f(0f, 0f, 0f),
                bogieRotation.get(org.joml.Quaternionf()),
                Vector3f(localScale.x().toFloat(), localScale.y().toFloat(), localScale.z().toFloat()),
                org.joml.Quaternionf(1f, 0f, 0f, 0f)
            )
        )
    }
}
