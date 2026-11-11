package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.Collections
import java.util.UUID

/** Pure endpoint-group geometry shared by live authoring and the offline save migration. */
object RailEndpointGeometry {
    /** Tests the same junction-side rule for two canonical AB endpoint frames. */
    fun canContinue(first: Curve.Point, firstEndpoint: RailEndpoint, second: Curve.Point, secondEndpoint: RailEndpoint): Boolean =
        outward(first, firstEndpoint).dot(outward(second, secondEndpoint)) < 0.0

    private fun outward(point: Curve.Point, endpoint: RailEndpoint): Vector3d {
        val tangent = Vector3d(point.tangent)
        require(tangent.x.isFinite() && tangent.y.isFinite() && tangent.z.isFinite() &&
            tangent.lengthSquared().isFinite() && tangent.lengthSquared() > 0.0) {
            "Endpoint tangent must be finite and nonzero"
        }
        if (endpoint == RailEndpoint.B) tangent.negate()
        return tangent.normalize()
    }
    /**
     * Derives symmetric legal routes inside one explicitly authored endpoint group.
     *
     * Ports must be distinct, colocated within [Curve.TOLERANCE], and have finite nonzero
     * tangents. Only legs extending into opposite open hemispheres can be traversed. This
     * classifies the sides of a junction; curve smoothness remains the curve creator's contract.
     * No proximity search adds a port, and returned sets/maps are immutable stable values.
     */
    fun legalContinuations(
        ports: Collection<RailEndpointRef>,
        curveById: (UUID) -> PrimeCurve
    ): Map<RailEndpointRef, Set<RailEndpointRef>> {
        require(ports.distinct().size == ports.size) { "endpoint group ports must be unique" }
        val ordered = ports.sorted()
        val points = ordered.associateWith { port ->
            val curve = curveById(port.railId)
            curve.getPointAt(if (port.endpoint == RailEndpoint.A) 0.0 else curve.totalArcLength)
        }
        ordered.forEachIndexed { index, first ->
            ordered.drop(index + 1).forEach { second ->
                require(points.getValue(first).position.distance(points.getValue(second).position) <= Curve.TOLERANCE) {
                    "Joined endpoints are not colocated: $first <-> $second"
                }
            }
        }
        val outward = ordered.associateWith { port -> outward(points.getValue(port), port.endpoint) }
        return Collections.unmodifiableMap(ordered.associateWith { from ->
            val targets = ordered.filterTo(linkedSetOf()) { to ->
                to != from && outward.getValue(from).dot(outward.getValue(to)) < 0.0
            }
            require(targets.size <= 2) { "A rail endpoint supports at most two legal continuations: $from" }
            Collections.unmodifiableSet(targets)
        })
    }
}
