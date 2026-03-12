package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.nms.PlatformEntity
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
        val platformEntity = PlatformEntity.spawn(Location(bukkitWorld, state.position.x(), state.position.y(), state.position.z()))
        entity = platformEntity
    }
}
