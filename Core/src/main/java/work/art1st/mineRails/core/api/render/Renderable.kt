package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.event.RenderableDestroyEvent
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.util.Destroyable

interface Renderable : Destroyable {
    val position: Vector3dc
    val direction: Vector3dc
    val normal: Vector3dc
    val velocity: Vector3dc
    val world: World

    override fun destroy() {
        EventManager.fire(RenderableDestroyEvent(this))
    }
}
