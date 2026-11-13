package work.art1st.javarailways.bukkit.render.backend

import org.bukkit.entity.ArmorStand

/** Native authority carrier shared by fixed seats and walking passengers. */
internal fun configureSeatCarrier(entity: ArmorStand) {
    entity.isPersistent = false
    entity.isSilent = true
    entity.isInvisible = true
    entity.isMarker = true
    entity.isSmall = true
    entity.setArms(false)
    entity.setBasePlate(false)
    entity.setGravity(false)
    entity.setNoPhysics(true)
    entity.isCollidable = false
}
