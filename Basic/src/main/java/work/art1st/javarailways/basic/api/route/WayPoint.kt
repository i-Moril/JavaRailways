package work.art1st.javarailways.basic.api.route

import java.util.UUID

/**
 * World-local route anchor identified by [id], without an operating direction or live rail handle.
 *
 * [arcLength] is a finite non-negative distance in metres along the rail's canonical AB curve.
 * Construction validates that distance only; the authoring/restore owner validates the ID and rail
 * reference when installing it. Station/depot and other operational meanings belong to their owner.
 *
 * @property id world-local route-anchor identity, validated on insertion into its collection.
 * @property railId persistent UUID of the physical rail containing this anchor.
 * @property arcLength distance in metres from the rail's canonical A endpoint along its AB curve.
 */
data class WayPoint(
    val id: String,
    val railId: UUID,
    val arcLength: Double
) {
    init {
        require(arcLength.isFinite() && arcLength >= 0.0) {
            "WayPoint $id on rail $railId has invalid arc length: $arcLength"
        }
    }
}
