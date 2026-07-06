package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Minecart
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World

class SeatModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val initialLocation: Location
) : EntityModel(world, state, attachedModels, animation, null) {
    init {
        spawnEntity()
    }

    override fun spawnEntity() {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val minecart = bukkitWorld.spawn(initialLocation, Minecart::class.java)
        minecart.isPersistent = false
        minecart.isSilent = true
        minecart.isInvisible = true
        minecart.isInvulnerable = true
        minecart.setGravity(false)
        minecart.setDisplayBlockData(Material.AIR.createBlockData())
        minecart.setDisplayBlockOffset(0)
        entity = minecart
    }
}
