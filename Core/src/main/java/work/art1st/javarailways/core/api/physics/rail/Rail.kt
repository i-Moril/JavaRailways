package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.plugin.Extendable
import java.util.UUID

/** A persistent rail with one canonical AB curve and two directional [RailView]s. */
interface Rail : Destroyable, Extendable<RenderableExtension<Rail>> {
    /** Persistent rail identity. */
    val id: UUID

    /** World that owns this rail. */
    val world: World

    /** Canonical AB geometry. Positions and arc lengths are measured in metres. */
    val curve: PrimeCurve

    /** Operational and geometry metadata. Mutations must follow the owning world's thread rules. */
    var metadata: RailMetadata

    /** Return the directional view for [direction]. */
    fun getView(direction: ViewDirection): RailView
}
