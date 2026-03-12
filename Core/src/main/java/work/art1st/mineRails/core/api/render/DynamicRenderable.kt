package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.WorldElement
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.util.Destroyable

interface DynamicRenderable : Renderable {
    var lastState: RenderableStateSnapshot
}
