package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.basic.api.route.planning.PhysicalRailDistance
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import kotlin.math.sqrt

internal data class RailMeasurementCapture(val worldId: UUID, val topology: TopologyState) {
    val selectedRails: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}
internal data class MeasuredRailPoint(val railId: UUID, val abMeters: Double, val x: Double, val y: Double, val z: Double)
internal data class RailMeasurementResult(val straightMeters: Double, val trackMeters: Double?)

/** Host geometry only; measuring does not depend on an installed/healthy signal definition runtime. */
internal class RailMeasurementBackend(private val infrastructure: InfrastructureResourceService,
    private val targets: RailTargetService) {
    fun capture(worldId: UUID) = RailMeasurementCapture(worldId, infrastructure.currentTopology(worldId))
    fun current(capture: RailMeasurementCapture): Boolean =
        infrastructure.currentTopology(capture.worldId).preservesRails(capture.topology, capture.selectedRails)

    fun select(capture: RailMeasurementCapture, railId: UUID, abMeters: Double): MeasuredRailPoint {
        capture.selectedRails += railId
        require(current(capture)) { "Selected rail changed; select both points again" }
        val curve: Curve = targets.findCurve(capture.worldId, railId).orElseThrow { IllegalArgumentException("Selected rail is unavailable") }
        require(abMeters.isFinite() && abMeters >= 0.0 && abMeters <= capture.topology.railLength(railId) &&
            abMeters <= curve.totalArcLength) { "Selected rail position is unavailable" }
        val p = curve.getPointAt(abMeters).position
        require(current(capture)) { "Topology changed; select both points again" }
        return MeasuredRailPoint(railId, abMeters, p.x(), p.y(), p.z())
    }

    fun measure(capture: RailMeasurementCapture, first: MeasuredRailPoint, second: MeasuredRailPoint): RailMeasurementResult {
        require(current(capture)) { "Topology changed; select both points again" }
        val track = PhysicalRailDistance.between(infrastructure.currentTopology(capture.worldId), first.railId, first.abMeters, second.railId, second.abMeters)
        val dx = first.x - second.x; val dy = first.y - second.y; val dz = first.z - second.z
        require(current(capture)) { "Topology changed; select both points again" }
        return RailMeasurementResult(sqrt(dx * dx + dy * dy + dz * dz), track)
    }
}
