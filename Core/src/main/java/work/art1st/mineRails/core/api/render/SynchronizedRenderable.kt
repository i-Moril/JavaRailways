package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.event.RenderableCommitEvent
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.util.SynchronizedObject
import work.art1st.mineRails.core.physics.World

abstract class SynchronizedRenderable : Renderable, SynchronizedObject {
    data class StateSnapshot(
        val position: Vector3dc,
        val direction: Vector3dc,
        val normal: Vector3dc,
        val velocity: Vector3dc,
        val world: World
    )

    protected lateinit var lastState: StateSnapshot

    fun takeSnapshot(): StateSnapshot {
        return StateSnapshot(
            position,
            direction,
            normal,
            velocity,
            world
        )
    }

    override fun commit() {
        EventManager.fire(RenderableCommitEvent(this, lastState))
        lastState = takeSnapshot()
    }
}