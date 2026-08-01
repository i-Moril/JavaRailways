package work.art1st.javarailways.core.util.trait.render

import work.art1st.javarailways.core.api.render.DynamicRenderable
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.util.trait.TrainComponentTrait

interface ExtendableRenderableTrait<T> : TrainComponentTrait<T, RenderableExtension<T>>, DynamicRenderable, Destroyable {

    override fun destroy() {
        forEachExtensionData {
            it.destroy()
        }
    }
}