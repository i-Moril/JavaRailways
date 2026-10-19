package work.art1st.javarailways.core.api.render

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.WorldElement

interface DynamicRenderable : Renderable {
    /**
     * Immutable render state captured at construction.
     *
     * This is only the fallback for handlers that run before the first published frame; per-frame
     * state comes from the immutable frame publication chain, never from a mutable "last state".
     */
    val initialRenderState: RenderableStateSnapshot
}
