package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.persistence.SavedTimetableActionTypeV1
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationStore
import work.art1st.javarailways.scriptworker.executeScript
import java.util.UUID

class TimetableScriptNormalizerTest {
    @Test
    fun `program movement to berth leaves door control to explicit instructions`() {
        val destinations = OperationalDestinationStore()
        destinations.add(OperationalDestination(
            id = "central@one", railId = UUID.randomUUID(), arcLengthInAbMeters = 10.0,
            role = DestinationRole.StationBerthRole(StationId("central"), "one", DoorSide.LEFT)
        ))
        val workerJson = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service('s', 'train', t => {
                t.check('depot')
                t.at(20).program('exit', {parameters:[], steps:[
                  {id:'move', type:'MOVE', destinationId:{value:'central@one'}, viaIds:{value:[]}},
                  {id:'open', type:'OPEN_DOORS'}
                ]}, {})
              })
            }
        """.trimIndent(), "demo")

        val actions = TimetableScriptNormalizer.normalize(TimetableSnapshotCodec.decode(workerJson), destinations)
            .services.single().steps.mapNotNull { it.action }
        assertEquals(listOf(SavedTimetableActionTypeV1.DESTINATION, SavedTimetableActionTypeV1.OPEN_DOORS),
            actions.map { it.type })
        assertEquals(listOf("central@one"), actions[0].wayPointIds)
    }

    @Test
    fun `unified destination resolves to station call from current world metadata`() {
        val destinations = OperationalDestinationStore()
        destinations.add(OperationalDestination(
            id = "central@one",
            railId = UUID.randomUUID(),
            arcLengthInAbMeters = 10.0,
            role = DestinationRole.StationBerthRole(
                StationId("central"), "one", DoorSide.LEFT
            )
        ))
        val workerJson = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service('s', 'train', t =>
                t.at(100).pass('junction').destination('central@one', {
                  minimumDwellTicks: 40,
                  doorSide: 'LEFT'
                }))
            }
        """.trimIndent(), "demo")

        val normalized = TimetableScriptNormalizer.normalize(
            TimetableSnapshotCodec.decode(workerJson),
            destinations
        )
        val options = normalized.services.single().steps.first().action!!

        assertEquals(SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS, options.type)
        assertEquals("central", options.stationId)
        assertEquals("one", options.berthId)
        assertEquals(listOf("junction", "central@one"),
            normalized.services.single().steps.last().action!!.wayPointIds)
    }
}
