package work.art1st.mineRails.core.util.trait.render

import work.art1st.mineRails.core.api.render.DynamicRenderable
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.mineRails.core.util.trait.TrainComponentTrait

interface ExtendableRenderableTrait<T> : TrainComponentTrait<T, RenderableExtension<T>>, DynamicRenderable, Destroyable {

    override fun destroy() {
        forEachExtensionData {
            it.destroy()
        }
    }
}