package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.WorldElement

interface DynamicRenderable : Renderable {
    var lastState: RenderableStateSnapshot
}
