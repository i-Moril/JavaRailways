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
import work.art1st.mineRails.bukkit.config.CarPartsConfig
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.extension.renderChunkManager

/**
 * 车厢渲染扩展：从 car_model.yml 加载模型配置并构建车厢可视化模型。
 */
class CarExtension : TrainComponentExtension<Car>() {
    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun commit() {
        val renderManager = self.world.renderChunkManager
        renderManager.update(self, self.lastState.position, self.position)
        self.bogies.forEach {
            renderManager.update(it, it.lastState.position, it.position)
        }
        super.commit()
    }

    override fun destroy() {
        val renderManager = self.world.renderChunkManager
        renderManager.remove(self)
        self.bogies.forEach { renderManager.remove(it) }
        super.destroy()
    }

    override fun buildModel() {
        val carConfig = BukkitPlugin.INSTANCE.carModelConfig.car
        val bogieConfig = BukkitPlugin.INSTANCE.carModelConfig.bogie
        val carLength = self.length
        val carFloorHeight = self.floorHeight

        createCarParts(carConfig, carLength, carFloorHeight)
        createBogieParts(bogieConfig, carLength, carFloorHeight)
    }

    override fun updateModel() {
        val carPos = self.position
        val carLength = self.length
        val carRotationFinal = currentCarRotation()

        for (i in displayEntities.indices) {
            val (localPos, localScale) = computeLocalTransform(i, carLength)
            updateItemDisplay(displayEntities[i], localPos, localScale, carPos, carRotationFinal)
        }
    }

    private fun currentCarRotation(): Quaterniond {
        val direction = self.direction
        val normal = self.normal
        val right = Vector3d()
        // right = normal × direction（右手系）
        normal.cross(direction, right).normalize()

        // Matrix3d(a,b,c,d,e,f,g,h,i) fills row-major: Row0=(a,b,c), Row1=(d,e,f), Row2=(g,h,i)
        // Col0 = world dir of model+X = right, Col1 = normal, Col2 = direction
        // 然后取共轭（取反 X 轴）：q' = (w, -x, y, z)，将绕 Y 轴旋转方向取反
        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), direction.x(),
            right.y(), normal.y(), direction.y(),
            right.z(), normal.z(), direction.z()
        )
        val carRotation = Quaterniond().setFromNormalized(rotationMatrix)
        return Quaterniond(carRotation.w(), -carRotation.x(), carRotation.y(), carRotation.z())
    }

    override fun destroyModel() {
        for (entity in displayEntities) {
            entity.remove()
        }
        displayEntities.clear()
    }

    private val displayEntities = mutableListOf<ItemDisplay>()

    private fun computeLocalTransform(partIndex: Int, carLength: Double): Pair<Vector3dc, Vector3dc> {
        val carConfig = BukkitPlugin.INSTANCE.carModelConfig.car
        val bogieConfig = BukkitPlugin.INSTANCE.carModelConfig.bogie

        val floorParts = 1
        val wallParts = carConfig.walls.size
        val roofParts = if (carConfig.roof != null) 1 else 0
        val bogieParts = 1 + (if (bogieConfig.rail != null) 1 else 0)

        return when {
            partIndex < floorParts -> {
                val floor = carConfig.floor
                Pair(
                    Vector3d(floor.offset.x, floor.offset.y, floor.offset.z),
                    Vector3d(carLength, floor.scale.y, floor.scale.z)
                )
            }
            partIndex < floorParts + wallParts -> {
                val wallIdx = partIndex - floorParts
                val wall = carConfig.walls[wallIdx]
                val wallOffsetX = (wall.offset.x / 1.0) * carLength
                Pair(
                    Vector3d(wallOffsetX, wall.offset.y, wall.offset.z),
                    Vector3d(carLength, wall.scale.y, wall.scale.z)
                )
            }
            partIndex < floorParts + wallParts + roofParts -> {
                val roof = carConfig.roof ?: return Pair(Vector3d(), Vector3d(1.0, 1.0, 1.0))
                val roofOffsetX = (roof.offset.x / 1.0) * carLength
                Pair(
                    Vector3d(roofOffsetX, roof.offset.y, roof.offset.z),
                    Vector3d(carLength, roof.scale.y, roof.scale.z)
                )
            }
            partIndex < floorParts + wallParts + roofParts + bogieParts -> {
                val bogiePartIdx = partIndex - (floorParts + wallParts + roofParts)
                val frame = bogieConfig.frame
                if (bogiePartIdx == 0) {
                    val bogieOffset = self.bogieAOffset
                    Pair(
                        Vector3d(bogieOffset, frame.offset.y, frame.offset.z),
                        Vector3d(frame.scale.x, frame.scale.y, frame.scale.z)
                    )
                } else {
                    val rail = bogieConfig.rail ?: return Pair(Vector3d(), Vector3d(1.0, 1.0, 1.0))
                    val midOffset = (self.bogieAOffset + self.bogieBOffset) / 2
                    val railLen = kotlin.math.abs(self.bogieBOffset - self.bogieAOffset).coerceAtLeast(0.5)
                    Pair(
                        Vector3d(midOffset, rail.offset.y, rail.offset.z),
                        Vector3d(railLen, rail.scale.y, rail.scale.z)
                    )
                }
            }
            else -> Pair(Vector3d(), Vector3d(1.0, 1.0, 1.0))
        }
    }

    private fun createCarParts(carConfig: CarPartsConfig, carLength: Double, floorHeight: Double) {
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld

        // 底板
        addDisplayEntity(bukkitWorld, carConfig.floor.material,
            Vector3d(carConfig.floor.offset.x, carConfig.floor.offset.y, carConfig.floor.offset.z),
            Vector3d(carLength, carConfig.floor.scale.y, carConfig.floor.scale.z))

        // 侧壁
        for (wall in carConfig.walls) {
            val wallOffsetX = (wall.offset.x / 1.0) * carLength
            addDisplayEntity(bukkitWorld, wall.material,
                Vector3d(wallOffsetX, wall.offset.y, wall.offset.z),
                Vector3d(carLength, wall.scale.y, wall.scale.z))
        }

        // 顶棚
        carConfig.roof?.let { roof ->
            val roofOffsetX = (roof.offset.x / 1.0) * carLength
            addDisplayEntity(bukkitWorld, roof.material,
                Vector3d(roofOffsetX, roof.offset.y, roof.offset.z),
                Vector3d(carLength, roof.scale.y, roof.scale.z))
        }
    }

    private fun createBogieParts(bogieConfig: work.art1st.mineRails.bukkit.config.BogiePartsConfig, carLength: Double, floorHeight: Double) {
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val frame = bogieConfig.frame

        // 转向架A
        addDisplayEntity(bukkitWorld, frame.material,
            Vector3d(self.bogieAOffset, frame.offset.y, frame.offset.z),
            Vector3d(frame.scale.x, frame.scale.y, frame.scale.z))

        // 转向架B
        addDisplayEntity(bukkitWorld, frame.material,
            Vector3d(self.bogieBOffset, frame.offset.y, frame.offset.z),
            Vector3d(frame.scale.x, frame.scale.y, frame.scale.z))

        // 轨道
        bogieConfig.rail?.let { rail ->
            val midOffset = (self.bogieAOffset + self.bogieBOffset) / 2
            val railLen = kotlin.math.abs(self.bogieBOffset - self.bogieAOffset).coerceAtLeast(0.5)
            addDisplayEntity(bukkitWorld, rail.material,
                Vector3d(midOffset, rail.offset.y, rail.offset.z),
                Vector3d(railLen, rail.scale.y, rail.scale.z))
        }
    }

    private fun addDisplayEntity(
        bukkitWorld: org.bukkit.World,
        materialName: String,
        localPos: Vector3dc,
        localScale: Vector3dc
    ) {
        val material = try { Material.valueOf(materialName.uppercase()) } catch (e: Exception) { Material.STONE }
        val carRotation = currentCarRotation()
        val rotatedOffset = Vector3d(localPos.x(), localPos.y(), localPos.z())
        carRotation.transform(rotatedOffset)
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
                carRotation.get(org.joml.Quaternionf()),
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
        carPos: Vector3dc,
        carRotation: Quaterniond
    ) {
        val rotatedOffset = Vector3d(localPos.x(), localPos.y(), localPos.z())
        carRotation.transform(rotatedOffset)
        val worldPos = Vector3d(carPos.x(), carPos.y(), carPos.z()).add(rotatedOffset)

        // Display entities are tracked by their real server-side entity location.
        // Keep that location at the visible position; otherwise a train far from
        // world origin may never be sent to nearby players even if the transform
        // translation visually points there.
        entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))

        entity.setTransformation(
            Transformation(
                Vector3f(0f, 0f, 0f),
                carRotation.get(org.joml.Quaternionf()),
                Vector3f(localScale.x().toFloat(), localScale.y().toFloat(), localScale.z().toFloat()),
                org.joml.Quaternionf(1f, 0f, 0f, 0f)
            )
        )
    }
}
