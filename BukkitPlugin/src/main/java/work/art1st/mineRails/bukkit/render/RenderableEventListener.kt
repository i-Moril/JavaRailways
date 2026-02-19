package work.art1st.mineRails.bukkit.render

import work.art1st.mineRails.core.api.event.RenderableCommitEvent
import work.art1st.mineRails.core.api.event.RenderableDestroyEvent
import work.art1st.mineRails.core.api.event.processor.EventListener
import work.art1st.mineRails.core.api.event.processor.HandlesEvent

class RenderableEventListener : EventListener {
    @HandlesEvent
    fun onRenderableCommit(event: RenderableCommitEvent) {
        world.updateRenderablePosition(this, position, activeLocation.position)
    }

    @HandlesEvent
    fun onRenderableDestroy(event: RenderableDestroyEvent) {

    }
}