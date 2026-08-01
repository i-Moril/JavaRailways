package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.WorldElement
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.api.render.DynamicRenderable
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing

interface Bogie : WorldElement, DynamicRenderable, Extendable<RenderableExtension<Bogie>> {
    val location: RailLocationWithFacing

    fun peekCurve(distance: Double) = location.peekCurve(distance)

    @Throws(BogieException::class)
    fun moveForward(distance: Double)
}