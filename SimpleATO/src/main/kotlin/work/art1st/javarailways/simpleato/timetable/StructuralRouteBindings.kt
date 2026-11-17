package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.wayPoints
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.UUID

/** Metadata bindings are separate from topology: moving a waypoint need not replace its rail. */
internal class StructuralRouteBindings private constructor(
    destinations: Collection<Destination>, private val stationBinding: StationBinding?
) {
    private val destinations = java.util.List.copyOf(destinations)
    private data class Destination(val id: String, val railId: UUID, val arc: Double, val direction: ViewDirection?)
    private data class StationBinding(val ref: StationBerthRef, val railId: UUID, val arc: Double,
        val direction: ViewDirection?, val offset: Double, val tolerance: Double, val usableLength: Double?,
        val berthDwell: Long?, val stationDwell: Long, val releaseDistance: Double, val doorSide: DoorSide,
        val lifecycle: RailObjectLifecycleState)

    fun requireCurrent(world: World) {
        for (expected in destinations) require(destination(world, expected.id) == expected) {
            "retained structural destination binding changed: ${expected.id}"
        }
        stationBinding?.let { expected -> require(station(world, expected.ref) == expected) {
            "retained structural station binding changed: ${expected.ref}"
        } }
    }

    companion object {
        fun capture(world: World, destinationIds: Collection<String>, berth: StationBerthRef?) =
            StructuralRouteBindings(destinationIds.distinct().map { destination(world, it) }, berth?.let { station(world, it) })

        private fun destination(world: World, id: String): Destination {
            val point = requireNotNull(world.wayPoints[id]) { "structural destination is missing: $id" }
            return Destination(id, point.railId, point.arcLength, world.simpleAtoWorldData.destinations[id]?.approachDirection)
        }
        private fun station(world: World, ref: StationBerthRef): StationBinding {
            val store = world.simpleAtoWorldData.stations
            val station = requireNotNull(store[ref.stationId]) { "structural station is missing: ${ref.stationId}" }
            require(ref in station.berthRefs) { "structural berth no longer belongs to station: $ref" }
            val berth = requireNotNull(store.berth(ref)) { "structural berth is missing: $ref" }
            return StationBinding(ref, berth.location.rail.id, berth.location.arcLengthIn(ViewDirection.AB),
                berth.approachDirection, berth.stoppingOffsetMetersBefore, berth.positionToleranceMeters,
                berth.usableLengthMeters, berth.defaultDwellTicks, station.defaultDwellTicks,
                berth.releaseDistanceMetersAfter, berth.doorSide, berth.lifecycleState)
        }
    }
}
