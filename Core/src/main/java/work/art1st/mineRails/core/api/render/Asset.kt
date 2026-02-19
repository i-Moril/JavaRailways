package work.art1st.mineRails.core.api.render

import work.art1st.mineRails.core.util.Destroyable

interface Asset : Destroyable {
    fun updateState(lastState: SynchronizedRenderable.StateSnapshot, currentState: SynchronizedRenderable.StateSnapshot)
}
