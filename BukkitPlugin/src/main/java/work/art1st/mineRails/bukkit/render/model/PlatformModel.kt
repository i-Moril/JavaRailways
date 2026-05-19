package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.HappyGhast
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World

class PlatformModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>
) : EntityModel(world, state, attachedModels, animation, null) {
    init {
        entity?.isPersistent = false
        spawnEntity()
    }

    override fun spawnEntity() {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val location = Location(bukkitWorld, state.position.x(), state.position.y(), state.position.z())
        val platformEntity = bukkitWorld.spawn(location, HappyGhast::class.java)
        platformEntity.isPersistent = false
        platformEntity.isSilent = true
        platformEntity.isInvisible = true
        platformEntity.isInvulnerable = true
        platformEntity.setAI(false)
        platformEntity.setGravity(false)
        entity = platformEntity
    }
}
