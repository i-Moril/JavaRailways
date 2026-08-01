package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.persistence.SavedTimetableActionTypeV1
import work.art1st.javarailways.basic.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.scriptworker.executeScript
import java.util.UUID

class TimetableScriptNormalizerTest {
    @OptIn(ExtensionData::class)
    @Test
    fun `unified destination resolves to station call from current world metadata`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        val plugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        world.registerExtensionData(plugin, data)
        data.destinations.add(OperationalDestination(
            id = "central@one",
            displayName = "Central",
            railId = UUID.randomUUID(),
            arcLengthInAbMeters = 10.0,
            role = DestinationRole.StationBerthRole(
                StationId("central"), "Central", "one", DoorSide.LEFT
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
            world
        )
        val options = normalized.services.single().steps.first().action!!

        assertEquals(SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS, options.type)
        assertEquals("central", options.stationId)
        assertEquals("one", options.berthId)
        assertEquals(listOf("junction", "central@one"),
            normalized.services.single().steps.last().action!!.wayPointIds)
    }
}
