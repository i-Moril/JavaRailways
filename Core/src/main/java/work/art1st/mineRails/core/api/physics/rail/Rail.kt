package work.art1st.mineRails.core.api.physics.rail

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.util.Destroyable
import java.util.UUID

interface Rail : Destroyable, Extendable<RenderableExtension<Rail>> {
    val id: UUID
    val world: World
}
