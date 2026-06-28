package work.art1st.mineRails.core.api.render

import work.art1st.mineRails.core.api.util.plugin.Extension
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.api.util.Destroyable

interface RenderableExtension<T> : Extension<T>, Synchronizable, Destroyable {
}