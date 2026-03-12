package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.physics.WorldElement
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.render.DynamicRenderable
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing

interface Bogie : WorldElement, DynamicRenderable, RailObject, Extendable<RenderableExtension<Bogie>> {
    override val location: RailLocationWithFacing

    fun peekCurve(distance: Double) = location.peekCurve(distance)

    @Throws(BogieException::class)
    fun moveForward(distance: Double)
}